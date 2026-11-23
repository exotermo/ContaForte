package dev.contaforte.imports.dedup;

import static dev.contaforte.imports.ImportItemStatus.DUPLICATE;
import static dev.contaforte.imports.ImportItemStatus.INVALID;
import static dev.contaforte.imports.ImportItemStatus.NEW;
import static dev.contaforte.imports.ImportItemStatus.POSSIBLE_DUPLICATE;
import static org.assertj.core.api.Assertions.assertThat;

import dev.contaforte.AbstractIntegrationTest;
import dev.contaforte.accounts.Account;
import dev.contaforte.accounts.AccountRepository;
import dev.contaforte.accounts.AccountType;
import dev.contaforte.imports.ImportBatch;
import dev.contaforte.imports.ImportBatchRepository;
import dev.contaforte.imports.ImportFormat;
import dev.contaforte.imports.ImportItem;
import dev.contaforte.imports.ImportItemRepository;
import dev.contaforte.imports.ImportItemStatus;
import dev.contaforte.imports.ImportStatus;
import dev.contaforte.imports.parse.CsvMapping;
import dev.contaforte.imports.parse.CsvParser;
import dev.contaforte.imports.parse.OfxParser;
import dev.contaforte.imports.parse.ParseResult;
import dev.contaforte.imports.parse.ParsedRow;
import dev.contaforte.transactions.Transaction;
import dev.contaforte.transactions.TransactionRepository;
import dev.contaforte.transactions.TransactionSource;
import dev.contaforte.transactions.TransactionType;
import dev.contaforte.users.Role;
import dev.contaforte.users.User;
import dev.contaforte.users.UserRepository;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

class ImportClassifierTest extends AbstractIntegrationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-23T12:00:00Z"), ZoneOffset.UTC);
    private final OfxParser ofx = new OfxParser(CLOCK);
    private final CsvParser csv = new CsvParser(CLOCK);

    @Autowired ImportClassifier classifier;
    @Autowired UserRepository users;
    @Autowired AccountRepository accounts;
    @Autowired TransactionRepository transactions;
    @Autowired ImportBatchRepository imports;
    @Autowired ImportItemRepository items;

    // ---------------------------------------------------------------- apoio

    private static byte[] fixture(String name) {
        String dir = System.getenv("FIXTURES_DIR");
        try {
            return Files.readAllBytes(Path.of(dir != null ? dir : "../../datasets/synthetic/imports").resolve(name));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private UUID novoUsuario() {
        return users.saveAndFlush(new User(UUID.randomUUID() + "@exemplo.com", "hash", Role.USER)).getId();
    }

    private UUID novaConta(UUID dono) {
        return accounts.saveAndFlush(new Account(dono, "Conta " + UUID.randomUUID(), AccountType.CHECKING_ACCOUNT, BigDecimal.ZERO)).getId();
    }

    private ParseResult ofx(String name) {
        return ofx.parse(fixture(name));
    }

    private ParseResult csv(String name) {
        byte[] bytes = fixture(name);
        return csv.parse(bytes, CsvMapping.fromMap(csv.suggest(bytes).mapping()));
    }

    /** O que a confirmação fará (M3.4): cria uma transação para cada linha válida, com o identificador do extrato. */
    private ImportBatch importar(UUID dono, UUID conta, ParseResult parsed, ImportFormat format) {
        ImportBatch b = imports.saveAndFlush(new ImportBatch(dono, conta, format, "extrato", "c".repeat(64), 1, Instant.now().plus(1, ChronoUnit.HOURS)));
        b.setCounts((int) parsed.validCount(), 0, 0, (int) parsed.invalidCount());
        b.setStatus(ImportStatus.CONFIRMED);
        b.setConfirmedAt(Instant.now());
        b.setImportedRows((int) parsed.validCount());
        imports.saveAndFlush(b);
        for (ParsedRow r : parsed.rows()) {
            if (!r.valid()) continue;
            Transaction t = new Transaction(dono, conta, r.type(), r.amount(), r.occurredAt());
            t.setDescription(r.description());
            t.setMerchant(r.merchant());
            t.markImported(b.getId(), r.externalId(), format == ImportFormat.OFX ? TransactionSource.OFX : TransactionSource.CSV);
            transactions.save(t);
        }
        transactions.flush();
        return b;
    }

    private Transaction manual(UUID dono, UUID conta, TransactionType type, String valor, String iso, String descricao) {
        Transaction t = new Transaction(dono, conta, type, new BigDecimal(valor), Instant.parse(iso));
        t.setDescription(descricao);
        return transactions.saveAndFlush(t);
    }

    private static List<ImportItemStatus> statuses(Classification c) {
        return c.rows().stream().map(RowResult::status).toList();
    }

    // ---------------------------------------------------------------- do arquivo ao banco

    @Test
    void contaVaziaTodasAsDozeLinhasSaoNovasInclusiveAsDuasPadariasIdenticas() {
        UUID dono = novoUsuario();
        Classification c = classifier.classify(novaConta(dono), ofx("conta-corrente-1.ofx"));

        assertThat(c.rows()).hasSize(12);
        assertThat(c.count(NEW)).isEqualTo(12);
        assertThat(c.rows()).allSatisfy(r -> assertThat(r.selected()).isTrue());
    }

    @Test
    void reimportarOMesmoArquivoDeixaTudoDuplicada() {
        UUID dono = novoUsuario();
        UUID conta = novaConta(dono);
        importar(dono, conta, ofx("conta-corrente-1.ofx"), ImportFormat.OFX);

        Classification c = classifier.classify(conta, ofx("conta-corrente-1.ofx"));

        assertThat(c.count(DUPLICATE)).isEqualTo(12);
        assertThat(c.rows()).allSatisfy(r -> {
            assertThat(r.selected()).isFalse();
            assertThat(r.duplicateOf()).isNotNull();
            assertThat(r.reason()).isEqualTo(DedupEngine.R_EXTERNAL);
        });
        // cada linha apontou para uma transação diferente
        assertThat(c.rows().stream().map(RowResult::duplicateOf).distinct()).hasSize(12);
    }

    @Test
    void arquivoSobrepostoDuplicaAsQuatroDeMarcoEDeixaAsTresNovas() {
        UUID dono = novoUsuario();
        UUID conta = novaConta(dono);
        importar(dono, conta, ofx("conta-corrente-1.ofx"), ImportFormat.OFX);

        Classification c = classifier.classify(conta, ofx("conta-corrente-2.ofx"));

        assertThat(statuses(c)).containsExactly(DUPLICATE, DUPLICATE, DUPLICATE, DUPLICATE, NEW, NEW, NEW);
        assertThat(c.rows().subList(0, 4)).allSatisfy(r -> assertThat(r.reason()).isEqualTo(DedupEngine.R_EXTERNAL));
    }

    @Test
    void importarOsDoisArquivosEmSequenciaNaoDuplicaNada() {
        UUID dono = novoUsuario();
        UUID conta = novaConta(dono);
        importar(dono, conta, ofx("conta-corrente-1.ofx"), ImportFormat.OFX);
        ParseResult segundo = ofx("conta-corrente-2.ofx");
        Classification c = classifier.classify(conta, segundo);
        // confirma só as novas
        ParseResult soNovas = new ParseResult(segundo.rows().stream().filter(r -> r.externalId() != null && Integer.parseInt(r.externalId().substring(3)) > 12).toList(), List.of());
        importar(dono, conta, soNovas, ImportFormat.OFX);

        assertThat(c.count(NEW)).isEqualTo(3);
        assertThat(transactions.count()).isGreaterThanOrEqualTo(15);
        assertThat(classifier.classify(conta, segundo).count(DUPLICATE)).isEqualTo(7);   // agora o arquivo 2 inteiro já existe
    }

    // ---------------------------------------------------------------- lançamentos manuais e outros formatos

    @Test
    void lancamentoManualIgualAoDoExtratoEDuplicataProvavel() {
        UUID dono = novoUsuario();
        UUID conta = novaConta(dono);
        manual(dono, conta, TransactionType.EXPENSE, "18.50", "2026-03-03T15:00:00Z", "PIX ENVIADO CAFÉ DA PRAÇA");

        RowResult r = classifier.classify(conta, ofx("conta-corrente-1.ofx")).rows().get(1);   // 2ª linha do extrato

        assertThat(r.status()).isEqualTo(DUPLICATE);
        assertThat(r.reason()).isEqualTo(DedupEngine.R_SAME);
        assertThat(r.duplicateOf()).isNotNull();
    }

    @Test
    void lancamentoManualComOutraDescricaoVirPossivelDuplicata() {
        UUID dono = novoUsuario();
        UUID conta = novaConta(dono);
        manual(dono, conta, TransactionType.EXPENSE, "64.99", "2026-03-18T15:00:00Z", "remédio");

        Classification c = classifier.classify(conta, ofx("conta-corrente-1.ofx"));

        RowResult farmacia = c.rows().get(9);   // COMPRA FARMÁCIA EXEMPLO, 64,99, 18/03
        assertThat(farmacia.status()).isEqualTo(POSSIBLE_DUPLICATE);
        assertThat(farmacia.selected()).isFalse();
        assertThat(c.count(NEW)).isEqualTo(11);
    }

    @Test
    void transferenciaLancadaAMaoApareceComoPossivelNoExtratoDaContaDeOrigem() {
        UUID dono = novoUsuario();
        UUID conta = novaConta(dono);
        UUID poupanca = novaConta(dono);
        Transaction t = new Transaction(dono, conta, TransactionType.TRANSFER, new BigDecimal("210.00"), Instant.parse("2026-03-22T15:00:00Z"));
        t.setCounterpartAccountId(poupanca);
        t.setDescription("reserva");
        transactions.saveAndFlush(t);

        // o extrato da conta de ORIGEM mostra a saída de 210,00 (posto): possível; o extrato da POUPANÇA veria uma entrada
        assertThat(classifier.classify(conta, ofx("conta-corrente-1.ofx")).rows().get(11).status()).isEqualTo(POSSIBLE_DUPLICATE);
        ParsedRow entrada = ParsedRow.ok(1, Instant.parse("2026-03-22T15:00:00Z"), TransactionType.INCOME, new BigDecimal("210.00"), "TRANSF RECEBIDA", null, "P1");
        assertThat(classifier.classify(poupanca, new ParseResult(List.of(entrada), List.of())).rows().get(0).status()).isEqualTo(POSSIBLE_DUPLICATE);
        // e uma saída de 210,00 na poupança NÃO casa (a transferência só entrou lá)
        ParsedRow saida = ParsedRow.ok(1, Instant.parse("2026-03-22T15:00:00Z"), TransactionType.EXPENSE, new BigDecimal("210.00"), "X", null, "P2");
        assertThat(classifier.classify(poupanca, new ParseResult(List.of(saida), List.of())).rows().get(0).status()).isEqualTo(NEW);
    }

    @Test
    void csvDoBancoAComDocumentoIgualAoFitidDeOfxJaImportadoEDuplicataPorIdentificador() {
        UUID dono = novoUsuario();
        UUID conta = novaConta(dono);
        importar(dono, conta, ofx("conta-corrente-1.ofx"), ImportFormat.OFX);

        Classification c = classifier.classify(conta, csv("banco-a.csv"));

        assertThat(c.count(DUPLICATE)).isEqualTo(12);
        assertThat(c.rows()).allSatisfy(r -> assertThat(r.reason()).isEqualTo(DedupEngine.R_EXTERNAL));
    }

    @Test
    void csvDoBancoCSemIdentificadorCasaPorValorDiaETextoUmParaUmInclusiveAsPadariasIdenticas() {
        UUID dono = novoUsuario();
        UUID conta = novaConta(dono);
        importar(dono, conta, ofx("conta-corrente-1.ofx"), ImportFormat.OFX);

        Classification c = classifier.classify(conta, csv("banco-c.csv"));

        assertThat(c.count(DUPLICATE)).isEqualTo(12);   // as duas padarias do mesmo dia casam cada uma com a sua
        assertThat(c.rows().stream().map(RowResult::duplicateOf).distinct()).hasSize(12);
        assertThat(c.rows()).allSatisfy(r -> assertThat(r.reason()).isEqualTo(DedupEngine.R_SAME));
    }

    @Test
    void csvDoBancoBMisturaProvaveisEPossiveisConformeATextoDaDescricao() {
        UUID dono = novoUsuario();
        UUID conta = novaConta(dono);
        importar(dono, conta, ofx("conta-corrente-1.ofx"), ImportFormat.OFX);

        // nos dias ímpares a descrição do CSV é igual ao memo do OFX; nos pares vem "Nome, Memo" entre aspas
        Classification c = classifier.classify(conta, csv("banco-b.csv"));

        assertThat(c.count(DUPLICATE)).isEqualTo(3);            // dias 3, 5 e 15
        assertThat(c.count(POSSIBLE_DUPLICATE)).isEqualTo(9);   // os de dia par: mesmo valor e dia, texto diferente
        assertThat(c.count(NEW)).isZero();
        assertThat(c.rows().stream().map(RowResult::duplicateOf).distinct()).hasSize(12);   // cada uma consumiu uma existente diferente
    }

    // ---------------------------------------------------------------- o que NÃO conta como existente

    @Test
    void transacaoExcluidaOutraContaEOutroUsuarioNaoSaoCandidatas() {
        UUID dono = novoUsuario();
        UUID conta = novaConta(dono);
        UUID outraConta = novaConta(dono);
        UUID outro = novoUsuario();
        UUID contaDoOutro = novaConta(outro);
        Transaction excluida = manual(dono, conta, TransactionType.EXPENSE, "187.45", "2026-03-02T15:00:00Z", "COMPRA CARTAO MERCADO EXEMPLO");
        excluida.setDeletedAt(Instant.now());
        transactions.saveAndFlush(excluida);
        manual(dono, outraConta, TransactionType.EXPENSE, "187.45", "2026-03-02T15:00:00Z", "COMPRA CARTAO MERCADO EXEMPLO");
        manual(outro, contaDoOutro, TransactionType.EXPENSE, "187.45", "2026-03-02T15:00:00Z", "COMPRA CARTAO MERCADO EXEMPLO");

        Classification c = classifier.classify(conta, ofx("conta-corrente-1.ofx"));

        assertThat(c.rows().get(0).status()).isEqualTo(NEW);
        assertThat(c.count(NEW)).isEqualTo(12);
    }

    @Test
    void identificadorExistenteEmOutraDataContaComoDuplicataPorqueOIndiceUnicoValeSempre() {
        UUID dono = novoUsuario();
        UUID conta = novaConta(dono);
        // mesmo FITID já importado, mas com data bem diferente (fora da janela do arquivo)
        ImportBatch lote = importar(dono, conta, new ParseResult(List.of(
                ParsedRow.ok(1, Instant.parse("2025-01-10T15:00:00Z"), TransactionType.EXPENSE, new BigDecimal("1.00"), "ANTIGA", null, "FIT0001")), List.of()), ImportFormat.OFX);

        RowResult primeira = classifier.classify(conta, ofx("conta-corrente-1.ofx")).rows().get(0);

        assertThat(primeira.status()).isEqualTo(DUPLICATE);
        assertThat(primeira.reason()).isEqualTo(DedupEngine.R_EXTERNAL);
        assertThat(lote.getId()).isNotNull();
    }

    @Test
    void depoisDeExcluirAsTransacoesDaImportacaoOArquivoVoltaAserNovo() {
        UUID dono = novoUsuario();
        UUID conta = novaConta(dono);
        importar(dono, conta, ofx("conta-corrente-1.ofx"), ImportFormat.OFX);
        assertThat(classifier.classify(conta, ofx("conta-corrente-1.ofx")).count(DUPLICATE)).isEqualTo(12);

        // rollback lógico: o que a M3.4 fará
        for (Transaction t : transactions.findAll()) {
            if (t.getAccountId().equals(conta)) {
                t.setDeletedAt(Instant.now());
                transactions.save(t);
            }
        }
        transactions.flush();

        assertThat(classifier.classify(conta, ofx("conta-corrente-1.ofx")).count(NEW)).isEqualTo(12);
    }

    // ---------------------------------------------------------------- arquivos com linhas ruins e a prévia no banco

    @Test
    void linhasInvalidasViramInvalidasNaClassificacao() {
        UUID dono = novoUsuario();
        Classification c = classifier.classify(novaConta(dono), csvComMapeamento("banco-a-invalido.csv"));

        assertThat(c.count(INVALID)).isEqualTo(8);
        assertThat(c.count(NEW)).isEqualTo(2);
        assertThat(c.rows().stream().filter(r -> r.status() == INVALID)).allSatisfy(r -> {
            assertThat(r.reason()).isNotBlank();
            assertThat(r.selected()).isFalse();
        });
    }

    private ParseResult csvComMapeamento(String name) {
        return csv.parse(fixture(name), CsvMapping.fromMap(java.util.Map.of("delimiter", ";", "dateColumn", "Data", "amountColumn", "Valor", "descriptionColumn", "Histórico")));
    }

    @Test
    void asLinhasDaPreviaRespeitamAsRestricoesDoBancoParaTodoTipoDeClassificacao() {
        UUID dono = novoUsuario();
        UUID conta = novaConta(dono);
        importar(dono, conta, new ParseResult(ofx("conta-corrente-1.ofx").rows().subList(0, 2), List.of()), ImportFormat.OFX);
        manual(dono, conta, TransactionType.EXPENSE, "64.99", "2026-03-18T15:00:00Z", "remédio");

        // tem de tudo: duplicada por identificador (2), possível (1), novas e, com o outro arquivo, inválidas
        ParseResult misto = new ParseResult(java.util.stream.Stream.concat(ofx("conta-corrente-1.ofx").rows().stream(),
                csvComMapeamento("banco-a-invalido.csv").rows().stream().map(r -> r.valid()
                        ? ParsedRow.ok(100 + r.lineNo(), r.occurredAt(), r.type(), r.amount(), r.description(), null, null)
                        : ParsedRow.invalid(100 + r.lineNo(), r.error()))).toList(), List.of());
        Classification c = classifier.classify(conta, misto);
        assertThat(c.count(DUPLICATE)).isEqualTo(2);
        assertThat(c.count(POSSIBLE_DUPLICATE)).isEqualTo(1);
        assertThat(c.count(INVALID)).isEqualTo(8);

        ImportBatch previa = imports.saveAndFlush(new ImportBatch(dono, conta, ImportFormat.OFX, "misto.ofx", "d".repeat(64), 10, Instant.now().plus(24, ChronoUnit.HOURS)));
        previa.setCounts(c.count(NEW), c.count(DUPLICATE), c.count(POSSIBLE_DUPLICATE), c.count(INVALID));
        imports.saveAndFlush(previa);
        List<ImportItem> linhas = ImportClassifier.toItems(previa.getId(), c);

        items.saveAllAndFlush(linhas);   // nenhuma violação de CHECK/FK do V6

        assertThat(items.countByImportIdAndStatus(previa.getId(), NEW)).isEqualTo(c.count(NEW));
        assertThat(items.findByImportIdAndSelectedTrueOrderByLineNo(previa.getId())).hasSize(c.count(NEW));   // só as novas vêm marcadas
        assertThat(linhas.stream().filter(i -> i.getStatus() == DUPLICATE)).allSatisfy(i -> assertThat(i.getDuplicateOf()).isNotNull());
    }

    @Test
    void asLinhasGravadasDaDuplicataNaoPodemSerMarcadasMesmoSeAlguemTentar() {
        UUID dono = novoUsuario();
        UUID conta = novaConta(dono);
        ImportBatch previa = imports.saveAndFlush(new ImportBatch(dono, conta, ImportFormat.OFX, "x.ofx", "e".repeat(64), 1, Instant.now().plus(1, ChronoUnit.HOURS)));
        ImportItem i = new ImportItem(previa.getId(), 1, DUPLICATE);
        i.setOccurredAt(Instant.now());
        i.setType(TransactionType.EXPENSE);
        i.setAmount(BigDecimal.TEN);
        i.setSelected(true);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> items.saveAndFlush(i)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
