package dev.contaforte.imports.dedup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import dev.contaforte.common.AppTime;
import dev.contaforte.imports.ImportItemStatus;
import dev.contaforte.imports.parse.ParsedRow;
import dev.contaforte.imports.parse.TextNorm;
import dev.contaforte.transactions.TransactionType;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static dev.contaforte.imports.ImportItemStatus.DUPLICATE;
import static dev.contaforte.imports.ImportItemStatus.INVALID;
import static dev.contaforte.imports.ImportItemStatus.NEW;
import static dev.contaforte.imports.ImportItemStatus.POSSIBLE_DUPLICATE;

class DedupEngineTest {

    private static final TransactionType EXP = TransactionType.EXPENSE;
    private static final TransactionType INC = TransactionType.INCOME;
    /** meio-dia de São Paulo nos dias de março de 2026 */
    private static String d(int day) {
        return String.format("2026-03-%02dT15:00:00Z", day);
    }

    private static ParsedRow row(int line, String iso, TransactionType type, String amount, String desc, String ext) {
        return ParsedRow.ok(line, Instant.parse(iso), type, new BigDecimal(amount), desc, null, ext);
    }

    private static Existing ex(long n, TransactionType effect, long cents, String iso, String desc, String ext) {
        Instant at = Instant.parse(iso);
        return new Existing(new UUID(0, n), effect, cents, at, at.atZone(AppTime.ZONE).toLocalDate(), ext, TextNorm.key(desc), PixId.extract(desc));
    }

    private static List<ImportItemStatus> statuses(Classification c) {
        return c.rows().stream().map(RowResult::status).toList();
    }

    // ---------------------------------------------------------------- o básico

    @Test
    void semNadaExistenteTudoEhNovaEMarcada() {
        Classification c = DedupEngine.classify(List.of(row(1, d(2), EXP, "10.00", "A", "F1"), row(2, d(3), INC, "20.00", "B", "F2")), List.of());

        assertThat(statuses(c)).containsExactly(NEW, NEW);
        assertThat(c.rows()).allSatisfy(r -> {
            assertThat(r.selected()).isTrue();
            assertThat(r.duplicateOf()).isNull();
        });
        assertThat(c.count(NEW)).isEqualTo(2);
    }

    @Test
    void linhasInvalidasPermanecemInvalidasComOMotivoESemSelecao() {
        Classification c = DedupEngine.classify(List.of(ParsedRow.invalid(1, "data inválida"), row(2, d(2), EXP, "10.00", "A", null)), List.of());

        assertThat(statuses(c)).containsExactly(INVALID, NEW);
        assertThat(c.rows().get(0).reason()).isEqualTo("data inválida");
        assertThat(c.rows().get(0).selected()).isFalse();
        assertThat(c.rows().get(0).text()).isNull();
    }

    @Test
    void listaVaziaGeraClassificacaoVazia() {
        assertThat(DedupEngine.classify(List.of(), List.of()).rows()).isEmpty();
    }

    // ---------------------------------------------------------------- identificador externo

    @Test
    void mesmoIdentificadorEDuplicataCertaMesmoComOutraDataEOutraDescricao() {
        Existing e = ex(1, EXP, 1000, d(2), "ANTIGA", "FIT1");
        Classification c = DedupEngine.classify(List.of(row(1, d(20), EXP, "99.99", "TOTALMENTE OUTRA", "FIT1")), List.of(e));

        RowResult r = c.rows().get(0);
        assertThat(r.status()).isEqualTo(DUPLICATE);
        assertThat(r.duplicateOf()).isEqualTo(e.id());
        assertThat(r.reason()).isEqualTo(DedupEngine.R_EXTERNAL);
        assertThat(r.selected()).isFalse();
    }

    @Test
    void reimportarOMesmoArquivoDeixaTudoDuplicada() {
        List<ParsedRow> arquivo = new ArrayList<>();
        List<Existing> banco = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            arquivo.add(row(i, d(i), EXP, i + ".00", "COMPRA " + i, "FIT" + i));
            banco.add(ex(i, EXP, i * 100L, d(i), "COMPRA " + i, "FIT" + i));
        }
        Classification c = DedupEngine.classify(arquivo, banco);

        assertThat(c.count(DUPLICATE)).isEqualTo(12);
        assertThat(c.rows()).allSatisfy(r -> assertThat(r.selected()).isFalse());
    }

    @Test
    void arquivoSobrepostoSoMarcaComoNovasAsLinhasQueNaoExistem() {
        List<ParsedRow> arquivo = new ArrayList<>();
        List<Existing> banco = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            arquivo.add(row(i, d(i), EXP, i + ".00", "COMPRA " + i, "FIT" + i));
            if (i <= 4) banco.add(ex(i, EXP, i * 100L, d(i), "COMPRA " + i, "FIT" + i));
        }
        assertThat(statuses(DedupEngine.classify(arquivo, banco))).containsExactly(DUPLICATE, DUPLICATE, DUPLICATE, DUPLICATE, NEW, NEW);
    }

    @Test
    void identificadorRepetidoNoProprioArquivoMarcaASegundaComoDuplicata() {
        Classification c = DedupEngine.classify(List.of(row(1, d(2), EXP, "10.00", "A", "SAME"), row(2, d(3), EXP, "20.00", "B", "SAME")), List.of());

        assertThat(statuses(c)).containsExactly(NEW, DUPLICATE);
        assertThat(c.rows().get(1).reason()).isEqualTo(DedupEngine.R_FILE_REPEAT);
        assertThat(c.rows().get(1).duplicateOf()).isNull();
    }

    @Test
    void identificadorExistenteNuncaViraNovaMesmoQueOutraLinhaTenhaCasadoPorTexto() {
        // a linha 1 (sem id) casaria por texto com a transação que tem FIT9; a linha 2 TEM o id FIT9 e é a dona dela
        Existing e = ex(1, EXP, 1000, d(2), "CAFE", "FIT9");
        Classification c = DedupEngine.classify(List.of(row(1, d(2), EXP, "10.00", "CAFE", null), row(2, d(2), EXP, "10.00", "CAFE", "FIT9")), List.of(e));

        assertThat(c.rows().get(1).status()).isEqualTo(DUPLICATE);   // pelo identificador (certeza)
        assertThat(c.rows().get(1).reason()).isEqualTo(DedupEngine.R_EXTERNAL);
        assertThat(c.rows().get(0).status()).isEqualTo(NEW);          // sobrou: a transação já foi do dono dela
    }

    // ---------------------------------------------------------------- contagem de ocorrências (o erro clássico)

    @Test
    void doisCafesIdenticosNoMesmoArquivoSemNadaNoBancoSaoDuasNovas() {
        Classification c = DedupEngine.classify(List.of(row(1, d(10), EXP, "23.80", "PADARIA SAO JOAO", null), row(2, d(10), EXP, "23.80", "PADARIA SAO JOAO", null)), List.of());
        assertThat(statuses(c)).containsExactly(NEW, NEW);
    }

    @Test
    void doisIdenticosNoArquivoEUmNoBancoSoOPrimeiroEDuplicata() {
        Existing e = ex(1, EXP, 2380, d(10), "PADARIA SAO JOAO", null);
        Classification c = DedupEngine.classify(List.of(row(1, d(10), EXP, "23.80", "PADARIA SAO JOAO", null), row(2, d(10), EXP, "23.80", "PADARIA SAO JOAO", null)), List.of(e));

        assertThat(statuses(c)).containsExactly(DUPLICATE, NEW);
        assertThat(c.rows().get(0).duplicateOf()).isEqualTo(e.id());
        assertThat(c.rows().get(0).reason()).isEqualTo(DedupEngine.R_SAME);
    }

    @Test
    void tresNoArquivoEDoisNoBancoSoAUltimaEhNova() {
        List<ParsedRow> arquivo = List.of(row(1, d(10), EXP, "5.00", "CAFE", null), row(2, d(10), EXP, "5.00", "CAFE", null), row(3, d(10), EXP, "5.00", "CAFE", null));
        List<Existing> banco = List.of(ex(1, EXP, 500, d(10), "CAFE", null), ex(2, EXP, 500, d(10), "CAFE", null));

        Classification c = DedupEngine.classify(arquivo, banco);

        assertThat(statuses(c)).containsExactly(DUPLICATE, DUPLICATE, NEW);
        assertThat(c.rows().get(0).duplicateOf()).isNotEqualTo(c.rows().get(1).duplicateOf());   // cada existente consumida uma vez só
    }

    @Test
    void doisNoBancoEUmNoArquivoSoConsomeUm() {
        Classification c = DedupEngine.classify(List.of(row(1, d(10), EXP, "5.00", "CAFE", null)), List.of(ex(1, EXP, 500, d(10), "CAFE", null), ex(2, EXP, 500, d(10), "CAFE", null)));
        assertThat(statuses(c)).containsExactly(DUPLICATE);
    }

    @Test
    void doisIdenticosComIdsDiferentesNoBancoEReimportadosPorIdSaoDuasDuplicatas() {
        List<Existing> banco = List.of(ex(1, EXP, 2380, d(10), "PADARIA", "FIT6"), ex(2, EXP, 2380, d(10), "PADARIA", "FIT7"));
        Classification c = DedupEngine.classify(List.of(row(1, d(10), EXP, "23.80", "PADARIA", "FIT6"), row(2, d(10), EXP, "23.80", "PADARIA", "FIT7")), banco);

        assertThat(statuses(c)).containsExactly(DUPLICATE, DUPLICATE);
        assertThat(c.rows().get(0).duplicateOf()).isEqualTo(new UUID(0, 1));
        assertThat(c.rows().get(1).duplicateOf()).isEqualTo(new UUID(0, 2));
    }

    // ---------------------------------------------------------------- duplicata provável

    @Test
    void mesmoValorDiaETextoNormalizadoEDuplicataMesmoContraLancamentoManual() {
        Existing manual = ex(1, EXP, 1850, d(3), "Café da Praça", null);
        Classification c = DedupEngine.classify(List.of(row(1, d(3), EXP, "18.50", "PIX ENVIADO  CAFE DA PRACA!", "FIT2")), List.of(manual));
        // "pix enviado cafe da praca" != "cafe da praca": texto diferente, então NÃO é provável, é possível
        assertThat(statuses(c)).containsExactly(POSSIBLE_DUPLICATE);

        Classification igual = DedupEngine.classify(List.of(row(1, d(3), EXP, "18.50", "CAFÉ  DA  PRAÇA!!", "FIT2")), List.of(manual));
        assertThat(statuses(igual)).containsExactly(DUPLICATE);
        assertThat(igual.rows().get(0).reason()).isEqualTo(DedupEngine.R_SAME);
    }

    @Test
    void linhaSemIdentificadorCasaComTransacaoImportadaQueTemId() {
        Existing importada = ex(1, EXP, 1000, d(2), "MERCADO", "FIT1");
        assertThat(statuses(DedupEngine.classify(List.of(row(1, d(2), EXP, "10.00", "MERCADO", null)), List.of(importada)))).containsExactly(DUPLICATE);
    }

    @Test
    void linhaComIdentificadorCasaComTransacaoQueNaoTemId() {
        Existing manual = ex(1, EXP, 1000, d(2), "MERCADO", null);
        assertThat(statuses(DedupEngine.classify(List.of(row(1, d(2), EXP, "10.00", "MERCADO", "FIT1")), List.of(manual)))).containsExactly(DUPLICATE);
    }

    @Test
    void doisIdentificadoresDiferentesSaoLancamentosDistintosDoBanco() {
        // mesmo valor, dia e texto, mas FITIDs diferentes dos dois lados: pode ser um segundo café de verdade
        Existing e = ex(1, EXP, 500, d(10), "CAFE", "FIT-A");
        Classification c = DedupEngine.classify(List.of(row(1, d(10), EXP, "5.00", "CAFE", "FIT-B")), List.of(e));

        assertThat(statuses(c)).containsExactly(POSSIBLE_DUPLICATE);   // não some sozinha: o usuário decide
        assertThat(c.rows().get(0).reason()).isEqualTo(DedupEngine.R_NEAR);
    }

    @Test
    void semTextoNaoHaDuplicataProvavelSoPossivel() {
        Existing e = ex(1, EXP, 500, d(10), "", null);
        Classification c = DedupEngine.classify(List.of(row(1, d(10), EXP, "5.00", null, null)), List.of(e));
        assertThat(statuses(c)).containsExactly(POSSIBLE_DUPLICATE);
    }

    @Test
    void tipoDiferenteOuValorDiferenteNaoCasam() {
        Existing e = ex(1, EXP, 1000, d(2), "MERCADO", null);
        assertThat(statuses(DedupEngine.classify(List.of(row(1, d(2), INC, "10.00", "MERCADO", null)), List.of(e)))).containsExactly(NEW);
        assertThat(statuses(DedupEngine.classify(List.of(row(1, d(2), EXP, "10.01", "MERCADO", null)), List.of(e)))).containsExactly(NEW);
    }

    // ---------------------------------------------------------------- possível duplicata

    @Test
    void mesmoValorEDiaComDescricaoDiferenteEPossivelENaoMarcada() {
        Existing e = ex(1, EXP, 6499, d(18), "farmácia", null);
        Classification c = DedupEngine.classify(List.of(row(1, d(18), EXP, "64.99", "COMPRA FARMACIA EXEMPLO", "FIT10")), List.of(e));

        RowResult r = c.rows().get(0);
        assertThat(r.status()).isEqualTo(POSSIBLE_DUPLICATE);
        assertThat(r.duplicateOf()).isEqualTo(e.id());
        assertThat(r.selected()).isFalse();
    }

    @Test
    void diaVizinhoEPossivelDoisDiasDepoisEhNova() {
        Existing e = ex(1, EXP, 5000, d(10), "ALGO", null);
        assertThat(statuses(DedupEngine.classify(List.of(row(1, d(9), EXP, "50.00", "OUTRA", null)), List.of(e)))).containsExactly(POSSIBLE_DUPLICATE);
        assertThat(statuses(DedupEngine.classify(List.of(row(1, d(11), EXP, "50.00", "OUTRA", null)), List.of(e)))).containsExactly(POSSIBLE_DUPLICATE);
        assertThat(statuses(DedupEngine.classify(List.of(row(1, d(12), EXP, "50.00", "OUTRA", null)), List.of(e)))).containsExactly(NEW);
        assertThat(statuses(DedupEngine.classify(List.of(row(1, d(8), EXP, "50.00", "OUTRA", null)), List.of(e)))).containsExactly(NEW);
    }

    @Test
    void odiaEOdeSaoPauloNaoOdeUtc() {
        // 23h30 de 09/03 em SP já é 10/03 em UTC: para o usuário, é o dia 9
        Existing e = ex(1, EXP, 5000, "2026-03-10T02:30:00Z", "ALGO", null);
        assertThat(e.day().toString()).isEqualTo("2026-03-09");
        // meio-dia de 10/03 em SP: um dia depois da transação existente, não o mesmo dia
        Classification c = DedupEngine.classify(List.of(row(1, d(10), EXP, "50.00", "ALGO", null)), List.of(e));
        assertThat(c.rows().get(0).status()).isEqualTo(POSSIBLE_DUPLICATE);   // diferença de 1 dia, não "mesmo dia" (que seria DUPLICATE)
        // já meio-dia de 09/03 em SP é o mesmo dia: provável
        assertThat(statuses(DedupEngine.classify(List.of(row(1, d(9), EXP, "50.00", "ALGO", null)), List.of(e)))).containsExactly(DUPLICATE);
    }

    @Test
    void possivelEscolheOMaisPertoEDepoisOMaisAntigoEConsomeUmaSo() {
        Existing antes = ex(1, EXP, 5000, d(9), "X", null);
        Existing mesmoDia = ex(2, EXP, 5000, d(10), "Y", null);
        Existing depois = ex(3, EXP, 5000, d(11), "Z", null);

        Classification c = DedupEngine.classify(List.of(row(1, d(10), EXP, "50.00", "OUTRA", null)), List.of(depois, antes, mesmoDia));
        assertThat(c.rows().get(0).duplicateOf()).isEqualTo(mesmoDia.id());

        // duas linhas, três candidatas a ±1 dia: cada uma consome uma diferente
        Classification duas = DedupEngine.classify(List.of(row(1, d(10), EXP, "50.00", "A", null), row(2, d(10), EXP, "50.00", "B", null)), List.of(antes, depois));
        assertThat(duas.rows().get(0).duplicateOf()).isEqualTo(antes.id());      // empate de distância: o mais antigo
        assertThat(duas.rows().get(1).duplicateOf()).isEqualTo(depois.id());
        // duas linhas para uma só candidata: a segunda é nova
        assertThat(statuses(DedupEngine.classify(List.of(row(1, d(10), EXP, "50.00", "A", null), row(2, d(10), EXP, "50.00", "B", null)), List.of(mesmoDia))))
                .containsExactly(POSSIBLE_DUPLICATE, NEW);
    }

    @Test
    void transferenciaLancadaAMaoPesaComoDespesaEAparecePorComoPossivelNoExtrato() {
        // Existing.effect já reflete o efeito na conta: transferência que SAI = despesa; a que CHEGA = receita
        Existing saiu = ex(1, EXP, 10000, d(5), "reserva", null);
        Existing chegou = ex(2, INC, 25000, d(12), "joana", null);
        Classification c = DedupEngine.classify(List.of(
                row(1, d(5), EXP, "100.00", "TRANSF ENVIADA POUPANCA", "F1"),
                row(2, d(12), INC, "250.00", "PIX RECEBIDO JOANA EXEMPLO", "F2")), List.of(saiu, chegou));
        assertThat(statuses(c)).containsExactly(POSSIBLE_DUPLICATE, POSSIBLE_DUPLICATE);
    }

    // ---------------------------------------------------------------- ordem das passadas

    @Test
    void possivelNuncaRoubaACorrespondenciaExataDeOutraLinha() {
        // a linha A (dia 11, texto outro) casaria por proximidade com a existente do dia 10; a linha B (dia 10, texto igual) é a dona dela
        Existing e = ex(1, EXP, 5000, d(10), "RESTAURANTE", null);
        Classification c = DedupEngine.classify(List.of(row(1, d(11), EXP, "50.00", "OUTRO TEXTO", null), row(2, d(10), EXP, "50.00", "RESTAURANTE", null)), List.of(e));

        assertThat(statuses(c)).containsExactly(NEW, DUPLICATE);
        assertThat(c.rows().get(1).duplicateOf()).isEqualTo(e.id());
    }

    @Test
    void resultadoNaoDependeDaOrdemDasLinhasNemDasExistentes() {
        List<ParsedRow> arquivo = new ArrayList<>(List.of(
                row(1, d(10), EXP, "5.00", "CAFE", null), row(2, d(10), EXP, "5.00", "PADARIA", null), row(3, d(11), EXP, "5.00", "OUTRA", null)));
        List<Existing> banco = new ArrayList<>(List.of(ex(1, EXP, 500, d(10), "CAFE", null), ex(2, EXP, 500, d(10), "ALGO", null)));
        long dupes = DedupEngine.classify(arquivo, banco).count(DUPLICATE);
        long possiveis = DedupEngine.classify(arquivo, banco).count(POSSIBLE_DUPLICATE);

        java.util.Collections.reverse(banco);
        assertThat(DedupEngine.classify(arquivo, banco).count(DUPLICATE)).isEqualTo(dupes);
        assertThat(DedupEngine.classify(arquivo, banco).count(POSSIBLE_DUPLICATE)).isEqualTo(possiveis);
    }

    // ---------------------------------------------------------------- PIX

    @Test
    void mesmoIdentificadorPixEDuplicataMesmoComTextoEDataDiferentes() {
        String pix = "E18236120202603101530s0123456789";
        Existing e = ex(1, INC, 25000, d(10), "PIX RECEBIDO JOANA " + pix, null);
        Classification c = DedupEngine.classify(List.of(row(1, d(11), INC, "250.00", "Transferência recebida - " + pix + " - JOANA E.", null)), List.of(e));

        assertThat(statuses(c)).containsExactly(DUPLICATE);
        assertThat(c.rows().get(0).reason()).isEqualTo(DedupEngine.R_PIX);
    }

    @Test
    void identificadoresPixDiferentesNaoCasam() {
        Existing e = ex(1, INC, 25000, d(10), "PIX E18236120202603101530s0123456789", null);
        Classification c = DedupEngine.classify(List.of(row(1, d(30), INC, "999.00", "PIX E18236120202603101531s0123456789", null)), List.of(e));
        assertThat(statuses(c)).containsExactly(NEW);
    }

    @Test
    void identificadoresPixQueDiferemSoPelaCaixaNaoCasam() {
        Existing e = ex(1, INC, 25000, d(10), "PIX E18236120202603101530abcdefghijk", null);
        Classification c = DedupEngine.classify(List.of(row(1, d(30), INC, "999.00", "PIX E18236120202603101530ABCDEFGHIJK", null)), List.of(e));
        assertThat(statuses(c)).containsExactly(NEW);
    }

    @Test
    void mesmoPixDuasVezesNoArquivoMarcaASegunda() {
        String pix = "E18236120202603101530s0123456789";
        Classification c = DedupEngine.classify(List.of(row(1, d(10), INC, "250.00", "PIX " + pix, null), row(2, d(10), INC, "250.00", "PIX " + pix, null)), List.of());
        assertThat(statuses(c)).containsExactly(NEW, DUPLICATE);
    }

    // ---------------------------------------------------------------- escala

    @Test
    void vinteMilLinhasContraVinteMilExistentesEmPoucosSegundos() {
        List<ParsedRow> arquivo = new ArrayList<>();
        List<Existing> banco = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            String iso = String.format("2026-03-%02dT15:00:00Z", i % 28 + 1);
            arquivo.add(row(i + 1, iso, EXP, (i % 900 + 1) + ".50", "COMPRA " + (i % 300), i % 2 == 0 ? "F" + i : null));
            banco.add(ex(i + 1, EXP, (i % 900 + 1) * 100L + 50, iso, "COMPRA " + (i % 300), i % 2 == 0 ? "F" + i : null));
        }
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            Classification c = DedupEngine.classify(arquivo, banco);
            assertThat(c.rows()).hasSize(20_000);
            assertThat(c.count(NEW)).isZero();   // tudo já existe
        });
    }
}
