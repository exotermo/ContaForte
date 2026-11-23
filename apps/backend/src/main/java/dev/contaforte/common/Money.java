package dev.contaforte.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.http.HttpStatus;

/**
 * Validação de valores monetários vindos da API (ADR-008, ADR-013). Nunca arredonda em silêncio: o banco
 * arredondaria 0,005 para 0,01, então valores com mais de 2 casas são recusados aqui com 422.
 */
public final class Money {

    /** Limite do NUMERIC(14,2): 12 dígitos inteiros. */
    private static final int MAX_INTEGER_DIGITS = 12;

    private Money() {
    }

    /** Devolve o valor com exatamente 2 casas, ou lança 422 se tiver mais de 2 casas ou estourar o limite. */
    public static BigDecimal parse(BigDecimal value, String field) {
        BigDecimal v = value.stripTrailingZeros();
        if (v.scale() > 2) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "O campo " + field + " aceita no máximo 2 casas decimais.");
        }
        // confere o tamanho ANTES de qualquer setScale (um expoente absurdo não pode alocar memória)
        if (v.signum() != 0 && v.precision() - v.scale() > MAX_INTEGER_DIGITS) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "O campo " + field + " excede o valor máximo permitido.");
        }
        return v.setScale(2, RoundingMode.UNNECESSARY);
    }

    /** Como {@link #parse}, mas exige valor maior que zero (valor de transação). */
    public static BigDecimal parsePositive(BigDecimal value, String field) {
        BigDecimal v = parse(value, field);
        if (v.signum() <= 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "O campo " + field + " deve ser maior que zero.");
        }
        return v;
    }
}
