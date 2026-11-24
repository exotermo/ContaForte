package dev.contaforte.classification;

import dev.contaforte.imports.parse.TextNorm;
import dev.contaforte.transactions.TransactionType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Motor de classificação (ADR-017): puro, sem banco, sem relógio e sem estado. A mesma entrada dá sempre a mesma saída,
 * seja qual for a ordem em que as regras chegam.
 *
 * <p>Para cada dimensão (categoria e contexto), o primeiro estágio que tem confiança de pelo menos 0,50 decide:
 * (1) regra do usuário, (2) favorecido conhecido, (3) regra padrão do sistema. Só receita e despesa são classificadas.
 * O motor nunca decide se aplica ou sugere por conta própria: devolve a confiança e a {@link Decision} correspondente.
 */
public final class ClassificationEngine {

    /** Versão do motor, gravada em cada feedback (dataset do ML, SDD §14). */
    public static final String VERSION = "regras-1";

    private static final int TEXT_LIMIT = 400;

    /** O que se classifica: texto do extrato e tipo. O favorecido PIX entra por {@link RecipientKnowledge}, já escolhido. */
    public record Subject(String description, String merchant, TransactionType type) {
    }

    private static final Comparator<Rule> USER_ORDER = Comparator.comparingInt(Rule::priority)
            .thenComparing(Rule::createdAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Rule::id);

    /** Mais específica primeiro (padrão mais longo, depois mais palavras), depois ordem estável por texto e id. */
    private static final Comparator<Rule> DEFAULT_ORDER = Comparator.comparingInt((Rule r) -> -r.pattern().length())
            .thenComparing(Rule::pattern)
            .thenComparing(Rule::id);

    private ClassificationEngine() {
    }

    /**
     * @param userRules    regras ativas do usuário
     * @param defaultRules regras padrão do sistema
     * @param recipient    o que se sabe do favorecido nesta direção; {@link RecipientKnowledge#EMPTY} se não é PIX ou é novo
     */
    public static Result classify(Subject subject, List<Rule> userRules, List<Rule> defaultRules, RecipientKnowledge recipient) {
        TransactionType type = subject.type();
        if (type != TransactionType.INCOME && type != TransactionType.EXPENSE) return Result.NONE;

        String[] texts = {clip(TextNorm.key(subject.description())), clip(TextNorm.key(subject.merchant()))};
        List<Rule> user = matching(userRules, type, texts, USER_ORDER);
        List<Rule> standard = matching(defaultRules, type, texts, DEFAULT_ORDER);
        RecipientKnowledge known = recipient == null ? RecipientKnowledge.EMPTY : recipient;

        Part category = decide(user, known.topCategory(), standard, true);
        Part context = decide(user, known.topContext(), standard, false);
        return new Result(category, context);
    }

    private static Part decide(List<Rule> user, RecipientKnowledge.Pick learned, List<Rule> standard, boolean forCategory) {
        for (Rule r : user) {
            UUID target = forCategory ? r.categoryId() : r.contextId();
            if (target != null) return Part.of(target, r.confidence(), Source.RULE);
        }
        if (learned != null && learned.confidence().decision() != Decision.NONE) {
            return Part.of(learned.id(), learned.confidence(), Source.RECIPIENT);
        }
        for (Rule r : standard) {
            UUID target = forCategory ? r.categoryId() : r.contextId();
            if (target != null) return Part.of(target, r.confidence(), Source.DEFAULT_RULE);
        }
        return Part.NONE;
    }

    private static List<Rule> matching(List<Rule> rules, TransactionType type, String[] texts, Comparator<Rule> order) {
        List<Rule> out = new ArrayList<>();
        for (Rule r : rules) {
            if (r.appliesTo(type, texts)) out.add(r);
        }
        out.sort(order);
        return out;
    }

    private static String clip(String key) {
        return key.length() > TEXT_LIMIT ? key.substring(0, TEXT_LIMIT) : key;
    }
}
