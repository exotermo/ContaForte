package dev.contaforte.classification;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * O que se aprendeu de um favorecido NUMA direção (receita ou despesa): votos por categoria e por contexto, cada voto
 * vindo de uma decisão do usuário (ADR-017). Imutável: aprender devolve um novo objeto. A confiança é calculada dos
 * votos, nunca guardada: com {@code n} votos e {@code k} na mais votada, vale {@code k / (n + 1)}.
 */
public record RecipientKnowledge(Map<UUID, Integer> categoryVotes, Map<UUID, Integer> contextVotes) {

    public static final RecipientKnowledge EMPTY = new RecipientKnowledge(Map.of(), Map.of());

    public RecipientKnowledge {
        categoryVotes = Map.copyOf(categoryVotes);
        contextVotes = Map.copyOf(contextVotes);
    }

    /** Soma um voto (nulos são ignorados: o usuário pode decidir só a categoria ou só o contexto). */
    public RecipientKnowledge withVote(UUID category, UUID context) {
        return new RecipientKnowledge(change(categoryVotes, category, +1), change(contextVotes, context, +1));
    }

    /** Retira um voto, sem nunca passar de zero (desfazer o aprendizado, ADR-018). */
    public RecipientKnowledge withoutVote(UUID category, UUID context) {
        return new RecipientKnowledge(change(categoryVotes, category, -1), change(contextVotes, context, -1));
    }

    /** O mais votado e a confiança dele, ou nulo se não há votos. Empate: o de menor id (determinístico). */
    Pick topCategory() {
        return top(categoryVotes);
    }

    Pick topContext() {
        return top(contextVotes);
    }

    record Pick(UUID id, Confidence confidence) {
    }

    private static Pick top(Map<UUID, Integer> votes) {
        UUID best = null;
        int bestVotes = 0;
        int total = 0;
        for (Map.Entry<UUID, Integer> e : votes.entrySet()) {
            total += e.getValue();
            if (e.getValue() > bestVotes || (e.getValue() == bestVotes && best != null && e.getKey().compareTo(best) < 0)) {
                best = e.getKey();
                bestVotes = e.getValue();
            }
        }
        return best == null ? null : new Pick(best, new Confidence(bestVotes, total + 1));
    }

    private static Map<UUID, Integer> change(Map<UUID, Integer> votes, UUID key, int delta) {
        if (key == null) return votes;
        Map<UUID, Integer> m = new HashMap<>(votes);
        int next = m.getOrDefault(key, 0) + delta;
        if (next > 0) m.put(key, next); else m.remove(key);
        return m;
    }
}
