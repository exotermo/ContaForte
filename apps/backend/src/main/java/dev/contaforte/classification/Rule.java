package dev.contaforte.classification;

import dev.contaforte.imports.parse.TextNorm;
import dev.contaforte.transactions.TransactionType;
import java.time.Instant;
import java.util.UUID;

/**
 * Regra determinística (do usuário ou padrão do sistema). O padrão é guardado já normalizado ({@link TextNorm#key}) e
 * comparado por PALAVRAS inteiras: "posto" casa com "COMPRA POSTO ABC", mas não com "COMPOSTO". Uma regra decide
 * categoria, contexto ou os dois.
 */
public record Rule(UUID id, MatchType match, String pattern, TransactionType type, UUID categoryId, UUID contextId,
                   int priority, Instant createdAt, Source source) {

    public Rule {
        pattern = TextNorm.key(pattern);
        if (pattern.length() < 3) throw new IllegalArgumentException("padrão curto demais");
        if (categoryId == null && contextId == null) throw new IllegalArgumentException("a regra precisa de categoria ou contexto");
        if (source != Source.RULE && source != Source.DEFAULT_RULE) throw new IllegalArgumentException("origem inválida: " + source);
        if (type != null && type != TransactionType.INCOME && type != TransactionType.EXPENSE) {
            throw new IllegalArgumentException("a regra só filtra receita ou despesa");
        }
    }

    Confidence confidence() {
        return source == Source.RULE ? Confidence.ONE : Confidence.DEFAULT_RULE;
    }

    boolean appliesTo(TransactionType t, String[] textKeys) {
        if (type != null && type != t) return false;
        for (String k : textKeys) {
            if (matches(k)) return true;
        }
        return false;
    }

    private boolean matches(String text) {
        if (text.isEmpty()) return false;
        return switch (match) {
            case EQUALS -> text.equals(pattern);
            case STARTS_WITH -> text.equals(pattern) || text.startsWith(pattern + " ");
            case CONTAINS -> text.equals(pattern) || text.startsWith(pattern + " ") || text.endsWith(" " + pattern)
                    || text.contains(" " + pattern + " ");
        };
    }
}
