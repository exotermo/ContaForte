package dev.contaforte.classification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ConfidenceTest {

    @ParameterizedTest(name = "{0}/{1} → {2}")
    @CsvSource({
            "1,1,AUTO", "9,10,AUTO", "17,20,AUTO",            // 0,85 exato aplica
            "6,7,AUTO",                                       // 0,857
            "84,100,SUGGEST", "5,6,SUGGEST",                  // 0,84 e 0,833 só sugerem
            "4,5,SUGGEST",                                    // 0,80
            "1,2,SUGGEST", "50,100,SUGGEST",                  // 0,50 exato sugere
            "49,100,NONE", "1,3,NONE", "2,7,NONE", "0,1,NONE"
    })
    void limiaresSaoComparadosSemArredondar(int num, int den, Decision esperado) {
        assertThat(new Confidence(num, den).decision()).isEqualTo(esperado);
    }

    @Test
    void ovalorGravadoNuncaSuperaOReal() {
        assertThat(new Confidence(6, 7).toBigDecimal()).isEqualByComparingTo(new BigDecimal("0.85"));   // 0,857 → 0,85, nunca 0,86
        assertThat(new Confidence(5, 6).toBigDecimal()).isEqualByComparingTo(new BigDecimal("0.83"));
        assertThat(new Confidence(84, 100).toBigDecimal()).isEqualByComparingTo(new BigDecimal("0.84"));
        assertThat(new Confidence(1, 1).toBigDecimal()).isEqualByComparingTo(new BigDecimal("1.00"));
        assertThat(new Confidence(0, 5).toBigDecimal()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void valorGravadoMaiorOuIgualA085SoVemDeConfiancaExataMaiorOuIgualA085() {
        for (int den = 1; den <= 60; den++) {
            for (int num = 0; num <= den; num++) {
                Confidence c = new Confidence(num, den);
                boolean gravadoAlto = c.toBigDecimal().compareTo(new BigDecimal("0.85")) >= 0;
                assertThat(gravadoAlto).as(num + "/" + den).isEqualTo(c.atLeastPercent(85));
                boolean gravadoMedio = c.toBigDecimal().compareTo(new BigDecimal("0.50")) >= 0;
                assertThat(gravadoMedio).as(num + "/" + den).isEqualTo(c.atLeastPercent(50));
            }
        }
    }

    @Test
    void comparaPorFracaoExata() {
        assertThat(new Confidence(1, 3)).isLessThan(new Confidence(2, 5));
        assertThat(new Confidence(2, 4).compareTo(new Confidence(1, 2))).isZero();
        assertThat(new Confidence(19, 20)).isGreaterThan(Confidence.DEFAULT_RULE);
        assertThat(new Confidence(7, 8)).isLessThan(Confidence.DEFAULT_RULE);   // 0,875 < 0,90
    }

    @Test
    void fracoesInvalidasSaoRecusadas() {
        assertThatThrownBy(() -> new Confidence(1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Confidence(-1, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Confidence(4, 3)).isInstanceOf(IllegalArgumentException.class);
    }
}
