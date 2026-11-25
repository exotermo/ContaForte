package dev.contaforte.classification;

import dev.contaforte.audit.AuditService;
import dev.contaforte.categories.CategoryRepository;
import dev.contaforte.categories.ContextRepository;
import dev.contaforte.classification.ClassificationEngine.Subject;
import dev.contaforte.common.ApiException;
import dev.contaforte.imports.parse.TextNorm;
import dev.contaforte.transactions.ClassificationStatus;
import dev.contaforte.transactions.Transaction;
import dev.contaforte.transactions.TransactionRepository;
import dev.contaforte.transactions.TransactionResponse;
import dev.contaforte.transactions.TransactionType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orquestra a classificação (ADR-017 e ADR-018): monta o que o motor precisa do banco, aplica o resultado às transações,
 * registra as decisões do usuário (feedback e votos do favorecido) e reclassifica em duas fases. Tudo é escopado ao
 * usuário. Descrições, nomes de favorecidos e padrões de regra são dados pessoais: auditoria e erros só levam ids e
 * contagens.
 */
@Service
public class ClassificationService {

    /** Limite de transações por reclassificação (a prévia avisa quando passa). */
    static final int RECLASSIFY_CAP = 20_000;
    private static final int SAMPLE = 20;
    private static final Sort ORDER = Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id"));

    /** O que o motor precisa de um usuário, carregado uma vez para classificar muitas linhas. */
    public record Snapshot(List<Rule> userRules, List<Rule> defaultRules, Map<String, RecipientKnowledge> recipients) {
    }

    private final CategorizationRuleRepository rules;
    private final RecipientStore recipients;
    private final TransactionRepository transactions;
    private final CategoryRepository categories;
    private final ContextRepository contexts;
    private final AuditService audit;
    private final ClassificationMetrics metrics;
    private volatile List<Rule> defaultRules;

    ClassificationService(CategorizationRuleRepository rules, RecipientStore recipients, TransactionRepository transactions,
                          CategoryRepository categories, ContextRepository contexts, AuditService audit,
                          ClassificationMetrics metrics) {
        this.rules = rules;
        this.recipients = recipients;
        this.transactions = transactions;
        this.categories = categories;
        this.contexts = contexts;
        this.audit = audit;
        this.metrics = metrics;
    }

    // ---------------------------------------------------------------- motor

    /** Regras padrão com os ids do catálogo do sistema (estáveis): carregadas uma vez. */
    List<Rule> defaultRules() {
        List<Rule> d = defaultRules;
        if (d == null) {
            d = DefaultRules.load(code -> categories.findByCodeAndUserIdIsNull(code).map(c -> c.getId()).orElse(null));
            defaultRules = d;
        }
        return d;
    }

    @Transactional(readOnly = true)
    public Snapshot snapshot(UUID userId) {
        List<Rule> user = new ArrayList<>();
        for (CategorizationRule r : rules.findEnabledOfUser(userId)) user.add(r.toRule());
        return new Snapshot(user, defaultRules(), recipients.knowledgeOf(userId));
    }

    public Result classify(Snapshot s, String description, String merchant, TransactionType type) {
        String direction = direction(type);
        RecipientKnowledge known = RecipientKnowledge.EMPTY;
        if (direction != null) {
            CounterpartyName.Result who = CounterpartyName.extract(description);
            if (who != null) known = s.recipients().getOrDefault(who.key() + "|" + direction, RecipientKnowledge.EMPTY);
        }
        return ClassificationEngine.classify(new Subject(description, merchant, type), s.userRules(), s.defaultRules(), known);
    }

    /**
     * Aplica o resultado do motor a uma transação SEM decisão do usuário: só preenche o que está vazio. Decisão automática
     * entra aplicada; sugestão fica à parte (nada é aplicado até o usuário confirmar). Devolve o estado resultante.
     */
    public ClassificationStatus applyResult(Transaction t, Result r) {
        Result eff = effective(t, r);
        Result.Summary sum = eff.summary();
        return applyStored(t, eff.category().decided() ? eff.category().id() : null, eff.category().decision(),
                eff.context().decided() ? eff.context().id() : null, eff.context().decision(),
                sum.decision() == Decision.NONE ? null : sum.source().name(),
                sum.decision() == Decision.NONE ? null : sum.confidence().toBigDecimal());
    }

    /**
     * Igual a {@link #applyResult}, a partir do que ficou guardado (a linha de uma prévia de importação): só preenche o que
     * está vazio; AUTO entra aplicado, SUGGEST fica à parte. O estado vem das decisões: alguma SUGGEST = sugerida.
     */
    public ClassificationStatus applyStored(Transaction t, UUID categoryId, Decision categoryDecision, UUID contextId, Decision contextDecision,
                                            String source, BigDecimal confidence) {
        UUID cat = t.getCategoryId();
        UUID ctx = t.getContextId();
        UUID suggestedCat = null;
        UUID suggestedCtx = null;
        boolean suggest = false;
        boolean any = false;
        if (t.getCategoryId() == null && categoryId != null && categoryDecision != Decision.NONE) {
            any = true;
            if (categoryDecision == Decision.AUTO) cat = categoryId; else { suggestedCat = categoryId; suggest = true; }
        }
        if (t.getContextId() == null && contextId != null && contextDecision != Decision.NONE) {
            any = true;
            if (contextDecision == Decision.AUTO) ctx = contextId; else { suggestedCtx = contextId; suggest = true; }
        }
        t.setCategoryId(cat);
        t.setContextId(ctx);
        if (!any) {
            t.setClassification(ClassificationStatus.NONE, null, null, null, null);
            return ClassificationStatus.NONE;
        }
        ClassificationStatus status = suggest ? ClassificationStatus.SUGGESTED : ClassificationStatus.AUTO;
        t.setClassification(status, source, confidence, suggestedCat, suggestedCtx);
        return status;
    }

    // ---------------------------------------------------------------- importação

    /**
     * Transação criada por uma importação com a escolha do usuário feita na prévia: vale como decisão dele (CONFIRMED),
     * ou "sem classificação" se ele escolheu nenhuma. O aprendizado é separado ({@link #learnImported}), depois de gravar.
     */
    public void applyUserChoice(Transaction t, UUID categoryId, UUID contextId) {
        t.setCategoryId(categoryId);
        t.setContextId(contextId);
        if (categoryId == null && contextId == null) {
            t.setClassification(ClassificationStatus.NONE, null, null, null, null);
        } else {
            t.setClassification(ClassificationStatus.CONFIRMED, Source.USER.name(), null, null, null);
        }
    }

    /** O que o motor tinha previsto para uma linha que o usuário então trocou (para o feedback). */
    public record Prediction(UUID categoryId, UUID contextId, String source, BigDecimal confidence) {
    }

    /**
     * Depois de gravar as transações de uma importação: as que o usuário decidiu na prévia ensinam o favorecido, e, se havia
     * previsão do motor, geram feedback (confirmou ou corrigiu). Linhas automáticas ou sugeridas não ensinam nada.
     */
    public void learnImported(UUID userId, Transaction t, Prediction predicted) {
        if (t.getClassificationStatus() != ClassificationStatus.CONFIRMED) return;
        learn(userId, t, t.getCategoryId(), t.getContextId());
        if (predicted != null) {
            boolean same = Objects.equals(predicted.categoryId(), t.getCategoryId()) && Objects.equals(predicted.contextId(), t.getContextId());
            feedback(userId, t.getId(), same ? "CONFIRMED" : "CORRECTED", predicted.categoryId(), t.getCategoryId(),
                    predicted.contextId(), t.getContextId(), predicted.source(), predicted.confidence());
        }
    }

    /**
     * Desfazer uma importação desfaz o aprendizado dela (ADR-018): tira os votos das decisões do usuário nas transações
     * dela e marca o feedback correspondente como revertido (o registro fica, como histórico). Chamar ANTES da exclusão.
     */
    @Transactional
    public int revertLearning(UUID userId, UUID importId) {
        List<Transaction> confirmed = transactions.findConfirmedByImport(importId, userId);
        for (Transaction t : confirmed) unlearn(userId, t, t.getDescription(), t.getCategoryId(), t.getContextId());
        if (!confirmed.isEmpty()) recipients.markFeedbackReverted(userId, confirmed.stream().map(Transaction::getId).toList());
        return confirmed.size();
    }

    /** O resultado só vale para as dimensões vazias: o que o usuário já preencheu não se toca. */
    private static Result effective(Transaction t, Result r) {
        return new Result(t.getCategoryId() != null ? Part.NONE : r.category(), t.getContextId() != null ? Part.NONE : r.context());
    }

    // ---------------------------------------------------------------- decisão do usuário

    public enum Action { CONFIRM, CORRECT }

    /** Regra a criar junto com a decisão ("sempre assim"): o padrão precisa casar com a própria transação. */
    public record RuleDraft(MatchType matchType, String pattern) {
    }

    @Transactional
    public TransactionResponse decide(UUID userId, UUID transactionId, Action action, UUID categoryId, UUID contextId, RuleDraft draft) {
        Transaction t = find(userId, transactionId);
        requireClassifiable(t);
        UUID cat;
        UUID ctx;
        if (action == Action.CONFIRM) {
            if (t.getClassificationStatus() != ClassificationStatus.SUGGESTED) {
                throw new ApiException(HttpStatus.CONFLICT, "Esta transação não tem sugestão para confirmar.");
            }
            cat = t.getSuggestedCategoryId() != null ? t.getSuggestedCategoryId() : t.getCategoryId();
            ctx = t.getSuggestedContextId() != null ? t.getSuggestedContextId() : t.getContextId();
        } else {
            if (categoryId == null && contextId == null) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Escolha uma categoria ou um contexto.");
            }
            cat = categoryId != null ? visibleCategory(userId, categoryId) : t.getCategoryId();
            ctx = contextId != null ? visibleContext(userId, contextId) : t.getContextId();
        }
        if (draft != null) validateDraftMatches(t, draft);
        UUID rule = null;
        if (draft != null) rule = createRuleFrom(userId, t, draft, cat, ctx);

        recordDecision(userId, t, t.getDescription(), cat, ctx);
        Transaction saved = transactions.saveAndFlush(t);
        audit.record(userId, action == Action.CONFIRM ? "CLASSIFICATION_CONFIRMED" : "CLASSIFICATION_CORRECTED", "transaction:" + transactionId,
                rule == null ? Map.of() : Map.of("ruleId", rule.toString()));
        return TransactionResponse.of(saved);
    }

    /** Transação nova com categoria ou contexto escolhidos à mão: é uma decisão do usuário. Chamar ANTES de gravar. */
    public void onManualCreate(UUID userId, Transaction t) {
        if (!classifiable(t) || (t.getCategoryId() == null && t.getContextId() == null)) return;
        recordDecision(userId, t, t.getDescription(), t.getCategoryId(), t.getContextId());
    }

    /**
     * Edição manual (PATCH) de categoria, contexto ou descrição. Chamar ANTES de gravar e ANTES de trocar a descrição na
     * entidade ({@code oldDescription} é a de antes): os votos antigos precisam sair do favorecido certo.
     */
    public void onManualEdit(UUID userId, Transaction t, String oldDescription, boolean categoryPresent, UUID newCategory,
                             boolean contextPresent, UUID newContext, String newDescription) {
        if (!classifiable(t)) {
            if (categoryPresent) t.setCategoryId(newCategory);
            if (contextPresent) t.setContextId(newContext);
            return;
        }
        UUID cat = categoryPresent ? newCategory : t.getCategoryId();
        UUID ctx = contextPresent ? newContext : t.getContextId();
        boolean fieldsChanged = !Objects.equals(cat, t.getCategoryId()) || !Objects.equals(ctx, t.getContextId());
        boolean descriptionChanged = !Objects.equals(oldDescription, newDescription);
        boolean suggestionPending = t.getClassificationStatus() == ClassificationStatus.SUGGESTED && (categoryPresent || contextPresent);
        if (!fieldsChanged && !suggestionPending && !(descriptionChanged && t.getClassificationStatus() == ClassificationStatus.CONFIRMED)) return;
        t.setDescription(newDescription);
        recordDecision(userId, t, oldDescription, cat, ctx);
    }

    /**
     * Grava a decisão do usuário na transação (ainda sem salvar): desfaz o aprendizado anterior, aplica categoria e contexto,
     * soma os votos do favorecido e, se havia uma previsão do motor (automática ou sugerida), registra o feedback.
     * Sem categoria nem contexto, a transação volta a "sem classificação".
     */
    private void recordDecision(UUID userId, Transaction t, String oldDescription, UUID cat, UUID ctx) {
        ClassificationStatus before = t.getClassificationStatus();
        boolean predicted = before == ClassificationStatus.AUTO || before == ClassificationStatus.SUGGESTED;
        UUID prevCat = t.getCategoryId() != null ? t.getCategoryId() : t.getSuggestedCategoryId();
        UUID prevCtx = t.getContextId() != null ? t.getContextId() : t.getSuggestedContextId();
        String predSource = predicted ? t.getClassificationSource() : null;
        BigDecimal predConfidence = predicted ? t.getClassificationConfidence() : null;

        if (before == ClassificationStatus.CONFIRMED) unlearn(userId, t, oldDescription, t.getCategoryId(), t.getContextId());

        t.setCategoryId(cat);
        t.setContextId(ctx);
        if (cat == null && ctx == null) {
            t.setClassification(ClassificationStatus.NONE, null, null, null, null);
        } else {
            t.setClassification(ClassificationStatus.CONFIRMED, Source.USER.name(), null, null, null);
            learn(userId, t, cat, ctx);
        }
        if (predicted) {
            boolean same = Objects.equals(prevCat, cat) && Objects.equals(prevCtx, ctx);
            feedback(userId, t.getId(), same ? "CONFIRMED" : "CORRECTED", prevCat, cat, prevCtx, ctx, predSource, predConfidence);
        }
    }

    private void feedback(UUID userId, UUID transactionId, String kind, UUID prevCat, UUID newCat, UUID prevCtx, UUID newCtx,
                          String source, BigDecimal confidence) {
        recipients.insertFeedback(userId, transactionId, kind, prevCat, newCat, prevCtx, newCtx, source, confidence);
        metrics.feedback(kind);
    }

    private void learn(UUID userId, Transaction t, UUID cat, UUID ctx) {
        String direction = direction(t.getType());
        CounterpartyName.Result who = CounterpartyName.extract(t.getDescription());
        if (direction == null || who == null) return;
        recipients.learn(userId, who, direction, t.getAmount(), t.getOccurredAt(), cat, ctx);
    }

    private void unlearn(UUID userId, Transaction t, String description, UUID cat, UUID ctx) {
        String direction = direction(t.getType());
        CounterpartyName.Result who = CounterpartyName.extract(description);
        if (direction == null || who == null) return;
        recipients.unlearn(userId, who.key(), direction, cat, ctx);
    }

    private void validateDraftMatches(Transaction t, RuleDraft draft) {
        if (draft.matchType() == null || draft.pattern() == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Informe o tipo de comparação e o padrão da regra.");
        }
        Rule probe = probe(draft.matchType(), draft.pattern(), t.getType());
        if (!probe.appliesTo(t.getType(), new String[] {TextNorm.key(t.getDescription()), TextNorm.key(t.getMerchant())})) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "O padrão da regra não casa com esta transação.");
        }
    }

    private UUID createRuleFrom(UUID userId, Transaction t, RuleDraft draft, UUID cat, UUID ctx) {
        String pattern = TextNorm.key(draft.pattern());
        if (rules.conditionExists(userId, draft.matchType(), pattern, t.getType(), null)) {
            throw new ApiException(HttpStatus.CONFLICT, "Já existe uma regra com esta condição.");
        }
        if (rules.countByUserId(userId) >= CategorizationRuleService.MAX_RULES) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Limite de " + CategorizationRuleService.MAX_RULES + " regras atingido.");
        }
        CategorizationRule r = rules.saveAndFlush(new CategorizationRule(userId, draft.matchType(), pattern, t.getType(), cat, ctx, 100));
        audit.record(userId, "RULE_CREATED", "rule:" + r.getId(), Map.of("origin", "decision"));
        return r.getId();
    }

    // ---------------------------------------------------------------- reclassificar em duas fases

    public record Proposal(UUID transactionId, UUID categoryId, UUID contextId, Decision decision, Source source, BigDecimal confidence) {
    }

    public record ReclassifyPreview(int eligible, int auto, int suggest, int none, boolean truncated, List<Proposal> sample) {
    }

    public record ReclassifyOutcome(int auto, int suggest, int unchanged) {
    }

    /** Prévia: o que a reclassificação faria agora, sem gravar nada. */
    @Transactional(readOnly = true)
    public ReclassifyPreview previewReclassify(UUID userId) {
        Snapshot snap = snapshot(userId);
        var page = transactions.findReclassifiable(userId, PageRequest.of(0, RECLASSIFY_CAP, ORDER));
        int auto = 0, suggest = 0, none = 0;
        List<Proposal> sample = new ArrayList<>();
        for (Transaction t : page.getContent()) {
            Result eff = effective(t, classify(snap, t.getDescription(), t.getMerchant(), t.getType()));
            Result.Summary s = eff.summary();
            switch (s.decision()) {
                case AUTO -> auto++;
                case SUGGEST -> suggest++;
                case NONE -> none++;
            }
            if (s.decision() != Decision.NONE && sample.size() < SAMPLE) {
                sample.add(new Proposal(t.getId(), eff.category().id(), eff.context().id(), s.decision(), s.source(), s.confidence().toBigDecimal()));
            }
        }
        return new ReclassifyPreview(page.getContent().size(), auto, suggest, none, page.getTotalElements() > RECLASSIFY_CAP, sample);
    }

    /** Aplica só se o resultado ainda é o da prévia (o que o usuário viu é o que se aplica); senão 409. */
    @Transactional
    public ReclassifyOutcome reclassify(UUID userId, int expectedAuto, int expectedSuggest) {
        Snapshot snap = snapshot(userId);
        var page = transactions.findReclassifiable(userId, PageRequest.of(0, RECLASSIFY_CAP, ORDER));
        List<Transaction> touched = new ArrayList<>();
        List<Result> results = new ArrayList<>();
        int auto = 0, suggest = 0, unchanged = 0;
        for (Transaction t : page.getContent()) {
            Result r = classify(snap, t.getDescription(), t.getMerchant(), t.getType());
            switch (effective(t, r).summary().decision()) {
                case AUTO -> auto++;
                case SUGGEST -> suggest++;
                case NONE -> unchanged++;
            }
            touched.add(t);
            results.add(r);
        }
        if (auto != expectedAuto || suggest != expectedSuggest) {
            throw new ApiException(HttpStatus.CONFLICT, "O resultado mudou desde a prévia. Confira a prévia de novo antes de aplicar.");
        }
        ClassificationMetrics.Tally tally = metrics.tally();
        for (int i = 0; i < touched.size(); i++) {
            tally.add(effective(touched.get(i), results.get(i)));   // antes de aplicar: depois, a categoria já estaria preenchida
            applyResult(touched.get(i), results.get(i));
        }
        transactions.saveAll(touched);
        tally.flush();
        audit.record(userId, "TRANSACTIONS_RECLASSIFIED", "user:" + userId, Map.of("auto", auto, "suggest", suggest));
        return new ReclassifyOutcome(auto, suggest, unchanged);
    }

    // ---------------------------------------------------------------- fila de revisão

    public record ReviewCounts(long suggested, long uncategorized) {
    }

    @Transactional(readOnly = true)
    public ReviewCounts reviewCounts(UUID userId) {
        return new ReviewCounts(transactions.countSuggested(userId), transactions.countUncategorized(userId));
    }

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<TransactionResponse> reviewQueue(UUID userId, int page, int size) {
        return transactions.findReviewQueue(userId, PageRequest.of(page, size, ORDER)).map(TransactionResponse::of);
    }

    // ---------------------------------------------------------------- apoio

    private Transaction find(UUID userId, UUID id) {
        return transactions.findByIdAndUserIdAndDeletedAtIsNull(id, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Transação não encontrada."));
    }

    private static boolean classifiable(Transaction t) {
        return t.getType() == TransactionType.INCOME || t.getType() == TransactionType.EXPENSE;
    }

    private static void requireClassifiable(Transaction t) {
        if (!classifiable(t)) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Só receitas e despesas são classificadas.");
    }

    private UUID visibleCategory(UUID userId, UUID id) {
        return categories.findVisibleById(id, userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Categoria não encontrada.")).getId();
    }

    private UUID visibleContext(UUID userId, UUID id) {
        return contexts.findVisibleById(id, userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Contexto não encontrado.")).getId();
    }

    static String direction(TransactionType type) {
        return switch (type) {
            case EXPENSE -> "OUT";
            case INCOME -> "IN";
            default -> null;
        };
    }

    /** Monta uma regra descartável só para testar o casamento do padrão com a transação. */
    private static Rule probe(MatchType match, String pattern, TransactionType type) {
        UUID any = UUID.randomUUID();
        try {
            return new Rule(any, match, pattern, type, any, null, 0, null, Source.RULE);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "O padrão da regra precisa ter ao menos 3 letras ou números.");
        }
    }
}
