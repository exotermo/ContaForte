package dev.contaforte.classification;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Confiança como fração exata {@code num/den}. Os limiares do ADR-017 (0,50 e 0,85) são comparados SEM arredondar:
 * 6/7 = 0,857 passa de 0,85; 17/20 = 0,85 passa; 84/100 não. O valor gravado no banco ({@link #toBigDecimal()}) é
 * truncado em 2 casas, nunca arredondado para cima, então um valor gravado maior ou igual a 0,85 sempre corresponde a
 * uma confiança exata maior ou igual a 0,85.
 */
public record Confidence(int num, int den) implements Comparable<Confidence> {

    public static final Confidence ONE = new Confidence(1, 1);
    /** Regra padrão do sistema (palavra-chave genérica). */
    public static final Confidence DEFAULT_RULE = new Confidence(9, 10);

    /** Confiança mínima para aplicar sozinho (ADR-017). */
    public static final int AUTO_PERCENT = 85;
    /** Confiança mínima para sugerir (ADR-017). */
    public static final int SUGGEST_PERCENT = 50;

    public Confidence {
        if (den <= 0 || num < 0 || num > den) throw new IllegalArgumentException("confiança fora de 0..1: " + num + "/" + den);
    }

    public boolean atLeastPercent(int percent) {
        return (long) num * 100 >= (long) percent * den;
    }

    public Decision decision() {
        if (atLeastPercent(AUTO_PERCENT)) return Decision.AUTO;
        if (atLeastPercent(SUGGEST_PERCENT)) return Decision.SUGGEST;
        return Decision.NONE;
    }

    /** Valor para o banco: 2 casas, truncado. */
    public BigDecimal toBigDecimal() {
        return BigDecimal.valueOf(num).divide(BigDecimal.valueOf(den), 2, RoundingMode.DOWN);
    }

    @Override
    public int compareTo(Confidence o) {
        return Long.compare((long) num * o.den, (long) o.num * den);
    }
}
