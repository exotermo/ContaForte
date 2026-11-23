package dev.contaforte.imports.dedup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class PixIdTest {

    private static final String ID = "E18236120202603101530s0123456789";   // a caixa mista é parte do identificador   // E + 8 + 12 + 11 = 32

    @Test
    void extraiOIdentificadorDeDentroDaDescricao() {
        assertThat(ID).hasSize(32);
        assertThat(PixId.extract("PIX ENVIADO JOANA " + ID)).isEqualTo(ID);
        assertThat(PixId.extract(ID + " PIX RECEBIDO")).isEqualTo(ID);
        assertThat(PixId.extract("REF: " + ID + ".")).isEqualTo(ID);
        assertThat(PixId.extract(ID)).isEqualTo(ID);
    }

    @Test
    void devolucaoComecaComDEACaixaDoIdentificadorEPreservada() {
        assertThat(PixId.extract("DEVOLUCAO D18236120202603101530s0123456789")).isEqualTo("D18236120202603101530s0123456789");
        // maiúscula e minúscula são identificadores diferentes: nunca se dobra a caixa
        assertThat(PixId.extract("x E18236120202603101530abcdefghijk")).isNotEqualTo(PixId.extract("x E18236120202603101530ABCDEFGHIJK"));
        // o prefixo precisa ser maiúsculo: "e1823..." solto no texto não é identificador
        assertThat(PixId.extract("pix e18236120202603101530s0123456789")).isNull();
    }

    @Test
    void naoConfundeOutrosTextosComIdentificador() {
        assertThat(PixId.extract(null)).isNull();
        assertThat(PixId.extract("")).isNull();
        assertThat(PixId.extract("COMPRA MERCADO EXEMPLO 12345")).isNull();
        assertThat(PixId.extract("E1823612020260310153")).isNull();                                     // curto demais
        assertThat(PixId.extract("E18236120202613101530s0123456789")).isNull();                         // mês 13
        assertThat(PixId.extract("E18236120202603321530s0123456789")).isNull();                         // dia 32
        assertThat(PixId.extract("E18236120202603102530s0123456789")).isNull();                         // hora 25
        assertThat(PixId.extract("E18236120202603101560s0123456789")).isNull();                         // minuto 60
        assertThat(PixId.extract("E1823612X202603101530s0123456789")).isNull();                         // letra onde só cabe dígito
        assertThat(PixId.extract("E18236120202603101530s012345678-")).isNull();                         // símbolo no final
        assertThat(PixId.extract("X" + ID)).isNull();                                                   // colado em outra palavra
        assertThat(PixId.extract(ID + "9")).isNull();                                                   // tem mais depois
    }

    @Test
    void duasBuscasNaoDaoCasamentoParaIdentificadoresDiferentes() {
        assertThat(PixId.extract("x " + ID)).isNotEqualTo(PixId.extract("x E18236120202603101531s0123456789"));
    }

    @Test
    void textoGiganteNaoDemoraNemUsaRegex() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            assertThat(PixId.extract("E".repeat(3_000_000))).isNull();
            assertThat(PixId.extract("E1".repeat(2_000_000))).isNull();
            assertThat(PixId.extract("x".repeat(3_000_000) + " " + ID)).isEqualTo(ID);
        });
    }
}
