package dev.contaforte.imports.parse;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TextNormTest {

    @Test
    void cleanJuntaEspacosRemoveControleEAparaOsExtremos() {
        assertThat(TextNorm.clean("  Padaria \t  São\n João  ", 200)).isEqualTo("Padaria São João");
        assertThat(TextNorm.clean("a\u0000b\u0007c", 200)).isEqualTo("abc");
        assertThat(TextNorm.clean("a  b", 200)).isEqualTo("a b");   // espaço sem quebra e espaço largo
        assertThat(TextNorm.clean("caf�", 200)).isEqualTo("caf");
    }

    @Test
    void cleanDevolveNuloParaVazio() {
        assertThat(TextNorm.clean(null, 10)).isNull();
        assertThat(TextNorm.clean("", 10)).isNull();
        assertThat(TextNorm.clean(" \t\n ", 10)).isNull();
    }

    @Test
    void cleanCortaNoLimiteSemPartirCaractereEmDois() {
        assertThat(TextNorm.clean("abcdefghij", 5)).isEqualTo("abcde");
        // "ab" + emojis de 2 unidades UTF-16: com limite 5 cabem "ab" e um emoji (4 unidades); o segundo não cabe inteiro
        String cortado = TextNorm.clean("ab" + "😀".repeat(5), 5);
        assertThat(cortado).isEqualTo("ab😀");
        assertThat(cortado.length()).isLessThanOrEqualTo(5);
        for (int max = 1; max <= 9; max++) {
            String c = TextNorm.clean("a😀b😀c😀d", max);
            if (c != null) {
                assertThat(c.length()).isLessThanOrEqualTo(max);
                // nenhuma metade de par substituto sobra
                assertThat(new String(c.getBytes(java.nio.charset.StandardCharsets.UTF_8), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(c);
            }
        }
        assertThat(TextNorm.clean("a ".repeat(1000), 50).length()).isLessThanOrEqualTo(50);
    }

    @Test
    void keyIgnoraAcentoCaixaEPontuacao() {
        assertThat(TextNorm.key("PADARIA  São João!")).isEqualTo("padaria sao joao");
        assertThat(TextNorm.key("padaria sao joao")).isEqualTo("padaria sao joao");
        assertThat(TextNorm.key("COMPRA*CARTÃO - Mercado Exemplo #12")).isEqualTo("compra cartao mercado exemplo 12");
        assertThat(TextNorm.key("Café da Praça")).isEqualTo(TextNorm.key("CAFE DA PRACA"));
        assertThat(TextNorm.key("Ação")).isEqualTo("acao");
    }

    @Test
    void keyDeVazioENuloEhVazia() {
        assertThat(TextNorm.key(null)).isEmpty();
        assertThat(TextNorm.key("")).isEmpty();
        assertThat(TextNorm.key("!!! ---")).isEmpty();
    }

    @Test
    void keyDiferenciaTextosDiferentesEMantemNumeros() {
        assertThat(TextNorm.key("PIX 1000")).isNotEqualTo(TextNorm.key("PIX 100"));
        assertThat(TextNorm.key("Loja A")).isNotEqualTo(TextNorm.key("Loja B"));
    }
}
