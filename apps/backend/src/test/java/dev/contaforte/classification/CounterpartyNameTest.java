package dev.contaforte.classification;

import static org.assertj.core.api.Assertions.assertThat;

import dev.contaforte.imports.parse.TextNorm;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class CounterpartyNameTest {

    private static final String E2E = "E12345678202603021530abcDEF12345";   // identificador PIX de 32 caracteres

    @ParameterizedTest(name = "{0}  →  {1}")
    @CsvSource(delimiter = '|', value = {
            "PIX ENVIADO CAFÉ DA PRAÇA                         | cafe da praca",
            "PIX RECEBIDO JOANA EXEMPLO                        | joana exemplo",
            "Pix enviado - Padaria São João                    | padaria sao joao",
            "pix recebido: maria da silva                      | maria da silva",
            "TRANSFERENCIA PIX JOAO PEDRO SANTOS               | joao pedro santos",
            "PIX TRANSF PARA CARLOS ALBERTO                    | carlos alberto",
            "PAGAMENTO PIX QR CODE RESTAURANTE ABC             | restaurante abc",
            "PIX ENVIADO DE ANA PAULA                          | ana paula",
            "PIX-ENVIADO LOJA DO ZE                            | loja do ze",
            "PIX DEVOLUCAO PEDRO HENRIQUE                      | pedro henrique",
            "PIX ENVIADO JOANA EXEMPLO 12/03                   | joana exemplo",
            "PIX ENVIADO JOANA EXEMPLO ***.123.456-** BANCO X  | joana exemplo",
            "PIX ENVIADO JOANA EXEMPLO CPF 123.456.789-00      | joana exemplo",
            "PIX ENVIADO JOANA EXEMPLO AG 1234 CC 56789-0      | joana exemplo",
            "PIX RECEBIDO JOANA EXEMPLO BANCO DO BRASIL        | joana exemplo",
            "PIX ENVIADO JOANA EXEMPLO E12345678202603021530abcDEF12345 | joana exemplo",
            "PIX ENVIADO MERCADO 3 IRMAOS                      | mercado 3 irmaos",
    })
    void extraiOFavorecidoDeDescricoesDePix(String descricao, String chave) {
        CounterpartyName.Result r = CounterpartyName.extract(descricao.trim());

        assertThat(r).isNotNull();
        assertThat(r.key()).isEqualTo(chave.trim());
        assertThat(TextNorm.key(r.display())).isEqualTo(r.key());   // o nome para mostrar e a chave descrevem o mesmo nome
    }

    @Test
    void oNomeParaMostrarPreservaCaixaEAcento() {
        CounterpartyName.Result r = CounterpartyName.extract("Pix recebido - Padaria São João");

        assertThat(r.display()).isEqualTo("Padaria São João");
    }

    @Test
    void variacoesDeCaixaAcentoEEspacosDaoAMesmaChave() {
        String a = CounterpartyName.extract("PIX ENVIADO  CAFÉ DA PRAÇA").key();
        String b = CounterpartyName.extract("pix enviado café da praça").key();
        String c = CounterpartyName.extract("Pix Enviado - Cafe da Praca 03/03").key();

        assertThat(a).isEqualTo(b).isEqualTo(c);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "   ",
            "COMPRA CARTAO MERCADO EXEMPLO",                 // não é PIX
            "PIX",                                           // só o marcador
            "PIX ENVIADO",                                   // só palavras de modelo
            "PIX ENVIADO " + E2E,                            // só o identificador
            "PIX ENVIADO 12/03/2026",                        // só data
            "PIX ENVIADO ***.123.456-**",                    // só documento mascarado
            "PIX ENVIADO ZE",                                // nome curto demais para identificar alguém
            "PIX - - -",
            "PIXEL LOJA DE JOGOS",                           // "pix" precisa ser a palavra inteira
    })
    void semFavorecidoNaoInventaUm(String descricao) {
        assertThat(CounterpartyName.extract(descricao)).isNull();
    }

    @Test
    void limitaOTamanhoEONumeroDePalavras() {
        CounterpartyName.Result r = CounterpartyName.extract("PIX ENVIADO " + "NOME ".repeat(50));

        assertThat(r.display().split(" ")).hasSize(CounterpartyName.MAX_TOKENS);
        assertThat(r.display().length()).isLessThanOrEqualTo(CounterpartyName.MAX_LENGTH);

        String longo = "PIX ENVIADO " + "A".repeat(500);
        CounterpartyName.Result l = CounterpartyName.extract(longo);
        assertThat(l == null || l.key().length() <= CounterpartyName.MAX_LENGTH).isTrue();
    }

    @Test
    void entradaHostilNuncaLancaEOResultadoSempreEhConsistente() {
        Random rnd = new Random(20261124L);   // semente fixa: falha reproduzível
        String alfabeto = "PIXpix ENVIADOrecebido-:;|,*/.0123456789áéçÃ \t\u0000​😀E";
        for (int n = 0; n < 5000; n++) {
            StringBuilder sb = new StringBuilder(n % 3 == 0 ? "PIX " : "");
            int len = rnd.nextInt(120);
            for (int i = 0; i < len; i++) sb.append(alfabeto.charAt(rnd.nextInt(alfabeto.length())));
            String entrada = sb.toString();

            CounterpartyName.Result r = CounterpartyName.extract(entrada);

            if (r != null) {
                assertThat(r.key()).as(entrada).hasSizeBetween(CounterpartyName.MIN_KEY, CounterpartyName.MAX_LENGTH);
                assertThat(TextNorm.key(r.display())).as(entrada).isEqualTo(r.key());
                assertThat(r.display()).as(entrada).doesNotContain("*").doesNotContain("\u0000");
            }
        }
    }

    @Test
    void entradaGigantePassaEmTempoLinear() {
        String enorme = "PIX " + "ENVIADO ".repeat(200_000);

        long t0 = System.nanoTime();
        CounterpartyName.extract(enorme);
        CounterpartyName.extract("PIX " + "-".repeat(500_000));

        assertThat((System.nanoTime() - t0) / 1_000_000).as("ms").isLessThan(2_000);
    }
}
