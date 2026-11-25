package dev.contaforte.classification;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Métricas da classificação (M4.6). Só rótulos de baixa cardinalidade e nenhum dado pessoal: quem decidiu ({@code source}),
 * o que se decidiu ({@code decision}) e o tipo de feedback. Nunca descrição, favorecido, regra, categoria ou valor.
 * Todos os contadores nascem em 0 na partida, senão o Prometheus perderia a primeira contagem de cada série.
 *
 * <p>{@code contaforte_classifications_total}: linhas que o motor classificou na prévia de uma importação ou numa
 * reclassificação aplicada, uma por linha (decisão do resumo). {@code NONE/NONE} = o motor não soube.
 */
@Component
public class ClassificationMetrics {

    private static final String[] SOURCES = {"RULE", "RECIPIENT", "DEFAULT_RULE"};
    private static final String[] DECISIONS = {"AUTO", "SUGGEST"};
    private static final String[] FEEDBACK = {"CONFIRMED", "CORRECTED"};

    private final MeterRegistry registry;

    ClassificationMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (String s : SOURCES) {
            for (String d : DECISIONS) registry.counter("contaforte.classifications", "source", s, "decision", d);
        }
        registry.counter("contaforte.classifications", "source", "NONE", "decision", "NONE");
        for (String k : FEEDBACK) registry.counter("contaforte.classification.feedback", "kind", k);
    }

    /** Acumula as decisões de um lote (ex.: 20 mil linhas) e soma tudo de uma vez, sem uma busca de contador por linha. */
    public final class Tally {
        private final int[][] counts = new int[Source.values().length][3];
        private int none;

        public void add(Result r) {
            Result.Summary s = r.summary();
            if (s.decision() == Decision.NONE) {
                none++;
                return;
            }
            counts[s.source().ordinal()][s.decision() == Decision.AUTO ? 0 : 1]++;
        }

        public void flush() {
            for (Source s : Source.values()) {
                if (s == Source.USER) continue;
                inc(s.name(), "AUTO", counts[s.ordinal()][0]);
                inc(s.name(), "SUGGEST", counts[s.ordinal()][1]);
            }
            inc("NONE", "NONE", none);
        }

        private void inc(String source, String decision, int n) {
            if (n > 0) registry.counter("contaforte.classifications", "source", source, "decision", decision).increment(n);
        }
    }

    public Tally tally() {
        return new Tally();
    }

    void feedback(String kind) {
        registry.counter("contaforte.classification.feedback", "kind", kind).increment();
    }
}
