package dev.contaforte.classification;

import dev.contaforte.audit.AuditService;
import dev.contaforte.categories.CategoryRepository;
import dev.contaforte.categories.ContextRepository;
import dev.contaforte.common.ApiException;
import dev.contaforte.imports.parse.TextNorm;
import dev.contaforte.transactions.TransactionType;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** CRUD das regras do usuário. O padrão pode conter nome de pessoa: fica fora de auditoria e de mensagens de erro. */
@Service
class CategorizationRuleService {

    static final int MAX_RULES = 200;

    private final CategorizationRuleRepository rules;
    private final CategoryRepository categories;
    private final ContextRepository contexts;
    private final AuditService audit;

    CategorizationRuleService(CategorizationRuleRepository rules, CategoryRepository categories, ContextRepository contexts, AuditService audit) {
        this.rules = rules;
        this.categories = categories;
        this.contexts = contexts;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    List<CategorizationRule> list(UUID userId) {
        return rules.findAllOfUser(userId);
    }

    @Transactional(readOnly = true)
    CategorizationRule get(UUID userId, UUID id) {
        return find(userId, id);
    }

    @Transactional
    CategorizationRule create(UUID userId, MatchType match, String rawPattern, TransactionType type, UUID categoryId, UUID contextId,
                              Integer priority, Boolean enabled) {
        String pattern = pattern(rawPattern);
        checkType(type);
        UUID cat = visibleCategory(userId, categoryId);
        UUID ctx = visibleContext(userId, contextId);
        if (cat == null && ctx == null) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "A regra precisa de uma categoria ou de um contexto.");
        if (rules.countByUserId(userId) >= MAX_RULES) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Limite de " + MAX_RULES + " regras atingido.");
        }
        if (rules.conditionExists(userId, match, pattern, type, null)) throw duplicate();
        CategorizationRule r = new CategorizationRule(userId, match, pattern, type, cat, ctx, priority(priority));
        if (enabled != null) r.setEnabled(enabled);
        CategorizationRule saved = save(r);
        audit.record(userId, "RULE_CREATED", "rule:" + saved.getId(), Map.of());
        return saved;
    }

    /** Campos {@code *Present} indicam o que veio no PATCH; nulo presente limpa tipo, categoria e contexto. */
    @Transactional
    CategorizationRule update(UUID userId, UUID id, UpdateFields in) {
        CategorizationRule r = find(userId, id);
        // valida tudo com os valores NOVOS antes de mexer na entidade: uma consulta com a entidade já alterada
        // descarregaria a mudança no banco (flush automático) e estouraria o índice único como erro 500
        MatchType match = in.match != null ? in.match : r.getMatchType();
        String pattern = in.pattern != null ? pattern(in.pattern) : r.getPattern();
        TransactionType type = r.getTransactionType();
        if (in.typePresent) {
            checkType(in.type);
            type = in.type;
        }
        UUID cat = in.categoryPresent ? visibleCategory(userId, in.categoryId) : r.getCategoryId();
        UUID ctx = in.contextPresent ? visibleContext(userId, in.contextId) : r.getContextId();
        int priority = in.priority != null ? priority(in.priority) : r.getPriority();
        if (cat == null && ctx == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "A regra precisa de uma categoria ou de um contexto.");
        }
        if (rules.conditionExists(userId, match, pattern, type, r.getId())) throw duplicate();
        r.setMatchType(match);
        r.setPattern(pattern);
        r.setTransactionType(type);
        r.setCategoryId(cat);
        r.setContextId(ctx);
        r.setPriority(priority);
        if (in.enabled != null) r.setEnabled(in.enabled);
        CategorizationRule saved = save(r);
        audit.record(userId, "RULE_UPDATED", "rule:" + id, Map.of());
        return saved;
    }

    @Transactional
    void delete(UUID userId, UUID id) {
        CategorizationRule r = find(userId, id);
        rules.delete(r);
        rules.flush();
        audit.record(userId, "RULE_DELETED", "rule:" + id, Map.of());
    }

    /** Campos do PATCH com a presença de cada um (ausente não muda; presente com nulo limpa onde permitido). */
    static final class UpdateFields {
        MatchType match;
        String pattern;
        boolean typePresent;
        TransactionType type;
        boolean categoryPresent;
        UUID categoryId;
        boolean contextPresent;
        UUID contextId;
        Integer priority;
        Boolean enabled;
    }

    // ---------------------------------------------------------------- validações

    private CategorizationRule find(UUID userId, UUID id) {
        return rules.findByIdAndUserId(id, userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Regra não encontrada."));
    }

    private CategorizationRule save(CategorizationRule r) {
        try {
            return rules.saveAndFlush(r);
        } catch (DataIntegrityViolationException e) {   // corrida com outra criação da mesma condição
            throw duplicate();
        }
    }

    private static ApiException duplicate() {
        return new ApiException(HttpStatus.CONFLICT, "Já existe uma regra com esta condição.");
    }

    private static String pattern(String raw) {
        String key = TextNorm.key(raw);
        if (key.length() < 3) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "O padrão precisa ter ao menos 3 letras ou números.");
        if (key.length() > 120) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "O padrão pode ter no máximo 120 caracteres.");
        return key;
    }

    private static void checkType(TransactionType type) {
        if (type != null && type != TransactionType.INCOME && type != TransactionType.EXPENSE) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "A regra só filtra por receita (INCOME) ou despesa (EXPENSE).");
        }
    }

    private static int priority(Integer p) {
        int v = p == null ? 100 : p;
        if (v < 0 || v > 10000) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "A prioridade vai de 0 a 10000 (menor vale primeiro).");
        return v;
    }

    private UUID visibleCategory(UUID userId, UUID id) {
        if (id == null) return null;
        return categories.findVisibleById(id, userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Categoria não encontrada.")).getId();
    }

    private UUID visibleContext(UUID userId, UUID id) {
        if (id == null) return null;
        return contexts.findVisibleById(id, userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Contexto não encontrado.")).getId();
    }
}
