package dev.contaforte.imports.parse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class AmountParserTest {

    @ParameterizedTest(name = "CSV: \"{0}\" → {1}")
    @CsvSource(delimiter = '|', value = {
            "10|10.00", "10,5|10.50", "10,50|10.50", "1.234,56|1234.56", "1234,56|1234.56", "1.234.567,89|1234567.89",
            "R$ 1.234,56|1234.56", "R$1.234,56|1234.56", "-R$ 10,00|-10.00", "R$ -10,00|-10.00", "-10,00|-10.00",
            "+10,00|10.00", "(123,45)|-123.45", "123,45-|-123.45", "12.5|12.50", "12.50|12.50", "1.500|1500.00",
            "1.234.567|1234567.00", "1,234.56|1234.56", "0,01|0.01", ",50|0.50", "007,5|7.50", "10.500|10500.00",
            "999999999999,99|999999999999.99", "0|0.00", "-0,00|0.00"})
    void csvAceitaOsFormatosBrasileirosEInternacionais(String in, String esperado) {
        assertThat(AmountParser.parseBr(in)).isEqualByComparingTo(new BigDecimal(esperado));
        assertThat(AmountParser.parseBr(in).scale()).isEqualTo(2);
    }

    @ParameterizedTest(name = "OFX: \"{0}\" → {1}")
    @CsvSource(delimiter = '|', value = {
            "-187.45|-187.45", "6500.00|6500.00", "-12,50|-12.50", "1234.5|1234.50", "-1,234.56|-1234.56", "-1.234,56|-1234.56",
            "5|5.00", "+250.00|250.00", ".5|0.50", "10.500|10.50"})
    void ofxUsaPontoComoDecimalMasAceitaVirgula(String in, String esperado) {
        assertThat(AmountParser.parseOfx(in)).isEqualByComparingTo(new BigDecimal(esperado));
    }

    @Test
    void mil_comPontoEhMilharNoCsvMasDecimalInvalidoNoOfx() {
        assertThat(AmountParser.parseBr("1.234")).isEqualByComparingTo("1234.00");              // "1.234" num CSV brasileiro
        assertThatThrownBy(() -> AmountParser.parseOfx("1.234")).hasMessageContaining("2 casas"); // no OFX o ponto é decimal
    }

    @Test
    void nuncaArredondaMaisDeDuasCasas() {
        for (String in : new String[] {"0,005", "10,999", "1,234", "-1,005", "12,3456"}) {
            assertThatThrownBy(() -> AmountParser.parseBr(in)).as(in).isInstanceOf(AmountParser.BadAmount.class).hasMessageContaining("2 casas");
        }
        for (String in : new String[] {"-1.005", "0.001", "10.999", "0,005"}) {
            assertThatThrownBy(() -> AmountParser.parseOfx(in)).as(in).isInstanceOf(AmountParser.BadAmount.class).hasMessageContaining("2 casas");
        }
        // com zero antes do ponto, "milhar" não faz sentido: recusa em vez de ler "0.001" como R$ 1,00
        assertThatThrownBy(() -> AmountParser.parseBr("0.001")).isInstanceOf(AmountParser.BadAmount.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "abc", "1,2,3", "1..5", "1.2.3,4", "10 reais", "--5", "1e3", "R$", "12-34", "1,000,000.5.5",
            "1.23.456", "..", ",,", "9999999999999,00", "0x10", "١٢٣", "1 000", "NaN", "Infinity", "(", ")", "-", "+"})
    void recusaOQueNaoEhValor(String in) {
        assertThatThrownBy(() -> AmountParser.parseBr(in)).isInstanceOf(AmountParser.BadAmount.class);
    }

    @Test
    void recusaNuloEValorLongoDemais() {
        assertThatThrownBy(() -> AmountParser.parseBr(null)).isInstanceOf(AmountParser.BadAmount.class);
        assertThatThrownBy(() -> AmountParser.parseOfx("1".repeat(41))).isInstanceOf(AmountParser.BadAmount.class);
        assertThatThrownBy(() -> AmountParser.parseBr("1".repeat(1_000_000))).isInstanceOf(AmountParser.BadAmount.class);
    }

    @Test
    void respeitaOLimiteDeDozeDigitosInteiros() {
        assertThat(AmountParser.parseBr("999.999.999.999,99")).isEqualByComparingTo("999999999999.99");
        assertThatThrownBy(() -> AmountParser.parseBr("1.000.000.000.000,00")).hasMessageContaining("limite");
    }
}
