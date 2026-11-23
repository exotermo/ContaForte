package dev.contaforte.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class MoneyTest {

    @Test
    void aceitaAteDuasCasasENormalizaParaEscalaDois() {
        assertThat(Money.parse(new BigDecimal("10"), "v")).isEqualTo(new BigDecimal("10.00"));
        assertThat(Money.parse(new BigDecimal("10.5"), "v")).isEqualTo(new BigDecimal("10.50"));
        assertThat(Money.parse(new BigDecimal("10.500"), "v")).isEqualTo(new BigDecimal("10.50"));  // zeros à direita
        assertThat(Money.parse(new BigDecimal("1E+3"), "v")).isEqualTo(new BigDecimal("1000.00"));
        assertThat(Money.parse(new BigDecimal("-200.01"), "v")).isEqualTo(new BigDecimal("-200.01"));
        assertThat(Money.parse(BigDecimal.ZERO, "v")).isEqualTo(new BigDecimal("0.00"));
    }

    @Test
    void recusaMaisDeDuasCasasSemArredondar() {
        for (String v : new String[] {"0.001", "0.005", "10.999", "-1.234"}) {
            assertThatThrownBy(() -> Money.parse(new BigDecimal(v), "valor"))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY))
                    .hasMessageContaining("2 casas");
        }
    }

    @Test
    void respeitaOLimiteDoBanco() {
        assertThat(Money.parse(new BigDecimal("999999999999.99"), "v")).isEqualTo(new BigDecimal("999999999999.99"));
        assertThat(Money.parse(new BigDecimal("-999999999999.99"), "v")).isEqualTo(new BigDecimal("-999999999999.99"));
        assertThatThrownBy(() -> Money.parse(new BigDecimal("1000000000000"), "v")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Money.parse(new BigDecimal("-1000000000000.00"), "v")).isInstanceOf(ApiException.class);
    }

    @Test
    void expoenteAbsurdoEhRecusadoSemAlocarMemoria() {
        assertThatThrownBy(() -> Money.parse(new BigDecimal("1E+999999999"), "v")).isInstanceOf(ApiException.class);
    }

    @Test
    void positivoRecusaZeroENegativo() {
        assertThat(Money.parsePositive(new BigDecimal("0.01"), "v")).isEqualTo(new BigDecimal("0.01"));
        assertThatThrownBy(() -> Money.parsePositive(BigDecimal.ZERO, "v")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Money.parsePositive(new BigDecimal("-1"), "v")).isInstanceOf(ApiException.class);
    }
}
