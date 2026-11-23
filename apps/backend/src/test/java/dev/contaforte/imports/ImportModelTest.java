package dev.contaforte.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.contaforte.AbstractIntegrationTest;
import dev.contaforte.accounts.Account;
import dev.contaforte.accounts.AccountRepository;
import dev.contaforte.accounts.AccountType;
import dev.contaforte.transactions.Transaction;
import dev.contaforte.transactions.TransactionRepository;
import dev.contaforte.transactions.TransactionSource;
import dev.contaforte.transactions.TransactionType;
import dev.contaforte.users.Role;
import dev.contaforte.users.User;
import dev.contaforte.users.UserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

class ImportModelTest extends AbstractIntegrationTest {

    private static final String SHA = "a".repeat(64);

    @Autowired ImportBatchRepository imports;
    @Autowired ImportItemRepository items;
    @Autowired AccountRepository accounts;
    @Autowired TransactionRepository transactions;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;

    private UUID novoUsuario() {
        return users.saveAndFlush(new User(UUID.randomUUID() + "@exemplo.com", "hash", Role.USER)).getId();
    }

    private UUID novaConta(UUID dono) {
        return accounts.saveAndFlush(new Account(dono, "Conta " + UUID.randomUUID(), AccountType.CHECKING_ACCOUNT, BigDecimal.ZERO)).getId();
    }

    private ImportBatch previa(UUID dono, UUID conta) {
        return imports.saveAndFlush(new ImportBatch(dono, conta, ImportFormat.OFX, "extrato.ofx", SHA, 1234,
                Instant.now().plus(24, ChronoUnit.HOURS)));
    }

    private ImportBatch confirmada(UUID dono, UUID conta) {
        ImportBatch b = previa(dono, conta);
        b.setCounts(2, 0, 0, 0);
        b.setStatus(ImportStatus.CONFIRMED);
        b.setConfirmedAt(Instant.now());
        b.setImportedRows(2);
        return imports.saveAndFlush(b);
    }

    private Transaction importada(UUID dono, UUID conta, ImportBatch lote, String externalId) {
        Transaction t = new Transaction(dono, conta, TransactionType.EXPENSE, new BigDecimal("10.00"), Instant.now());
        t.markImported(lote.getId(), externalId, TransactionSource.OFX);
        return t;
    }

    // ---------------------------------------------------------------- importação

    @Test
    void persisteAPreviaComMapeamentoJsonEPadroes() {
        UUID dono = novoUsuario();
        UUID conta = novaConta(dono);
        ImportBatch b = new ImportBatch(dono, conta, ImportFormat.CSV, "extrato.csv", SHA, 500, Instant.now().plus(24, ChronoUnit.HOURS));
        b.setMapping(Map.of("delimiter", ";", "dateColumn", "Data", "dateFormat", "dd/MM/yyyy"));
        b.setCounts(5, 2, 1, 1);

        ImportBatch salva = imports.saveAndFlush(b);
        imports.findById(salva.getId()).orElseThrow();   // recarrega do banco

        ImportBatch lida = imports.findByIdAndUserId(salva.getId(), dono).orElseThrow();
        assertThat(lida.getStatus()).isEqualTo(ImportStatus.PREVIEW);
        assertThat(lida.getTotalRows()).isEqualTo(9);
        assertThat(lida.getImportedRows()).isZero();
        assertThat(lida.getMapping()).containsEntry("delimiter", ";").containsEntry("dateFormat", "dd/MM/yyyy");
        assertThat(lida.getCreatedAt()).isNotNull();
        assertThat(lida.getFileSha256()).hasSize(64);
    }

    @Test
    void importacaoDeOutroUsuarioNaoApareceNaBuscaEscopada() {
        UUID ana = novoUsuario();
        UUID bia = novoUsuario();
        ImportBatch daAna = previa(ana, novaConta(ana));

        assertThat(imports.findByIdAndUserId(daAna.getId(), ana)).isPresent();
        assertThat(imports.findByIdAndUserId(daAna.getId(), bia)).isEmpty();
        assertThat(imports.findByUserIdOrderByCreatedAtDesc(bia, PageRequest.of(0, 10))).isEmpty();
    }

    @Test
    void contaDeOutroUsuarioEhRejeitadaPeloBanco() {
        UUID ana = novoUsuario();
        UUID bia = novoUsuario();
        UUID contaDaAna = novaConta(ana);

        assertThatThrownBy(() -> previa(bia, contaDaAna)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void formatoEStatusInvalidosSaoRejeitados() {
        UUID dono = novoUsuario();
        UUID conta = novaConta(dono);
        String sql = "insert into imports (user_id, account_id, format, status, file_name, file_sha256, file_size, expires_at) "
                + "values (?, ?, ?, ?, 'x', ?, 1, now())";
        assertThatThrownBy(() -> jdbc.update(sql, dono, conta, "PDF", "PREVIEW", SHA)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(sql, dono, conta, "OFX", "PENDENTE", SHA)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void contadoresPrecisamSomarOTotalENaoSerNegativos() {
        UUID dono = novoUsuario();
        UUID conta = novaConta(dono);
        String sql = "insert into imports (user_id, account_id, format, file_name, file_sha256, file_size, expires_at, "
                + "total_rows, new_rows, duplicate_rows, possible_rows, invalid_rows) values (?, ?, 'OFX', 'x', ?, 1, now(), ?, ?, ?, ?, ?)";
        assertThat(jdbc.update(sql, dono, conta, SHA, 4, 1, 1, 1, 1)).isEqualTo(1);                                       // soma bate
        assertThatThrownBy(() -> jdbc.update(sql, dono, conta, SHA, 5, 1, 1, 1, 1)).isInstanceOf(DataIntegrityViolationException.class);   // soma 4, total 5
        assertThatThrownBy(() -> jdbc.update(sql, dono, conta, SHA, 0, 5, -5, 0, 0)).isInstanceOf(DataIntegrityViolationException.class);  // negativo que "fecha" a soma
    }

    @Test
    void importadasNaoPodemSerMaisQueAsElegiveis() {
        UUID dono = novoUsuario();
        UUID conta = novaConta(dono);
        // 1 nova + 1 possível = no máximo 2 importáveis
        String sql = "insert into imports (user_id, account_id, format, status, file_name, file_sha256, file_size, confirmed_at, "
                + "total_rows, new_rows, duplicate_rows, possible_rows, invalid_rows, imported_rows) "
                + "values (?, ?, 'OFX', 'CONFIRMED', 'x', ?, 1, now(), 3, 1, 1, 1, 0, ?)";
        assertThat(jdbc.update(sql, dono, conta, SHA, 2)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update(sql, dono, conta, SHA, 3)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void cicloDeVidaExigeAsDatasCertas() {
        UUID dono = novoUsuario();
        UUID conta = novaConta(dono);
        String base = "insert into imports (user_id, account_id, format, status, file_name, file_sha256, file_size, expires_at, confirmed_at, rolled_back_at) "
                + "values (?, ?, 'OFX', ?, 'x', ?, 1, ?::timestamptz, ?::timestamptz, ?::timestamptz)";
        String agora = Instant.now().toString();

        // PREVIEW exige validade
        assertThatThrownBy(() -> jdbc.update(base, dono, conta, "PREVIEW", SHA, null, null, null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.update(base, dono, conta, "PREVIEW", SHA, agora, null, null)).isEqualTo(1);
        // CONFIRMED exige a data da confirmação
        assertThatThrownBy(() -> jdbc.update(base, dono, conta, "CONFIRMED", SHA, null, null, null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.update(base, dono, conta, "CONFIRMED", SHA, null, agora, null)).isEqualTo(1);
        // ROLLED_BACK exige as duas datas; e a data do rollback só existe nesse status
        assertThatThrownBy(() -> jdbc.update(base, dono, conta, "ROLLED_BACK", SHA, null, agora, null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.update(base, dono, conta, "ROLLED_BACK", SHA, null, agora, agora)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update(base, dono, conta, "CONFIRMED", SHA, null, agora, agora)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void avisaQueOArquivoJaFoiImportadoSoQuandoConfirmadoNaMesmaConta() {
        UUID dono = novoUsuario();
        UUID conta = novaConta(dono);
        UUID outraConta = novaConta(dono);
        previa(dono, conta);   // só prévia: não conta

        assertThat(imports.existsByAccountIdAndFileSha256AndStatus(conta, SHA, ImportStatus.CONFIRMED)).isFalse();
        confirmada(dono, conta);
        assertThat(imports.existsByAccountIdAndFileSha256AndStatus(conta, SHA, ImportStatus.CONFIRMED)).isTrue();
        assertThat(imports.existsByAccountIdAndFileSha256AndStatus(outraConta, SHA, ImportStatus.CONFIRMED)).isFalse();
        assertThat(imports.existsByAccountIdAndFileSha256AndStatus(conta, "b".repeat(64), ImportStatus.CONFIRMED)).isFalse();
    }

    @Test
    void contaPreviasEAchaAsVencidas() {
        UUID dono = novoUsuario();
        UUID conta = novaConta(dono);
        ImportBatch vencida = imports.saveAndFlush(new ImportBatch(dono, conta, ImportFormat.OFX, "a.ofx", SHA, 1, Instant.now().minusSeconds(60)));
        ImportBatch valida = previa(dono, conta);

        assertThat(imports.findByStatusAndExpiresAtBefore(ImportStatus.PREVIEW, Instant.now()))
                .extracting(ImportBatch::getId).contains(vencida.getId()).doesNotContain(valida.getId());
        assertThat(imports.countByUserIdAndCreatedAtAfter(dono, Instant.now().minusSeconds(3600))).isEqualTo(2);
        assertThat(imports.countByUserIdAndCreatedAtAfter(dono, Instant.now().plusSeconds(60))).isZero();
    }

    // ---------------------------------------------------------------- linhas da prévia

    private ImportItem linhaNova(ImportBatch lote, int n) {
        ImportItem i = new ImportItem(lote.getId(), n, ImportItemStatus.NEW);
        i.setOccurredAt(Instant.now());
        i.setType(TransactionType.EXPENSE);
        i.setAmount(new BigDecimal("12.34"));
        i.setDescription("Mercado Exemplo");
        i.setNormalizedText("mercado exemplo");
        i.setExternalId("FIT-" + n);
        i.setSelected(true);
        return i;
    }

    @Test
    void persisteLinhasNaOrdemDoArquivoEPagina() {
        UUID dono = novoUsuario();
        ImportBatch lote = previa(dono, novaConta(dono));
        for (int n = 3; n >= 1; n--) items.saveAndFlush(linhaNova(lote, n));
        ImportItem inv = new ImportItem(lote.getId(), 4, ImportItemStatus.INVALID);
        inv.setReason("valor inválido");
        items.saveAndFlush(inv);

        assertThat(items.findByImportIdOrderByLineNo(lote.getId(), PageRequest.of(0, 10)).map(ImportItem::getLineNo)).containsExactly(1, 2, 3, 4);
        assertThat(items.findByImportIdAndStatusOrderByLineNo(lote.getId(), ImportItemStatus.INVALID, PageRequest.of(0, 10)))
                .singleElement().satisfies(i -> assertThat(i.getReason()).isEqualTo("valor inválido"));
        assertThat(items.findByImportIdAndSelectedTrueOrderByLineNo(lote.getId())).extracting(ImportItem::getLineNo).containsExactly(1, 2, 3);
        assertThat(items.countByImportIdAndStatus(lote.getId(), ImportItemStatus.NEW)).isEqualTo(3);
        assertThat(items.findByImportIdOrderByLineNo(lote.getId(), PageRequest.of(1, 3)).getContent()).hasSize(1);
    }

    @Test
    void linhaValidaExigeDataTipoEValorPositivo() {
        UUID dono = novoUsuario();
        ImportBatch lote = previa(dono, novaConta(dono));
        String sql = "insert into import_items (import_id, line_no, status, occurred_at, type, amount) values (?, ?, 'NEW', ?::timestamptz, ?, ?::numeric)";
        String agora = Instant.now().toString();
        assertThat(jdbc.update(sql, lote.getId(), 1, agora, "EXPENSE", "10.00")).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update(sql, lote.getId(), 2, null, "EXPENSE", "10.00")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(sql, lote.getId(), 3, agora, null, "10.00")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(sql, lote.getId(), 4, agora, "EXPENSE", null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(sql, lote.getId(), 5, agora, "EXPENSE", "0.00")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(sql, lote.getId(), 6, agora, "EXPENSE", "-5.00")).isInstanceOf(DataIntegrityViolationException.class);
        // a linha só tem receita ou despesa: transferência não vem de extrato
        assertThatThrownBy(() -> jdbc.update(sql, lote.getId(), 7, agora, "TRANSFER", "10.00")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void linhaInvalidaPrecisaDeMotivoENaoDeDados() {
        UUID dono = novoUsuario();
        ImportBatch lote = previa(dono, novaConta(dono));
        assertThat(jdbc.update("insert into import_items (import_id, line_no, status, reason) values (?, 1, 'INVALID', 'data inválida')", lote.getId())).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("insert into import_items (import_id, line_no, status) values (?, 2, 'INVALID')", lote.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void soNovaEPossivelPodemSerSelecionadas() {
        UUID dono = novoUsuario();
        ImportBatch lote = previa(dono, novaConta(dono));
        String sql = "insert into import_items (import_id, line_no, status, occurred_at, type, amount, reason, selected) "
                + "values (?, ?, ?, now(), 'EXPENSE', 1, ?, true)";
        assertThat(jdbc.update(sql, lote.getId(), 1, "NEW", null)).isEqualTo(1);
        assertThat(jdbc.update(sql, lote.getId(), 2, "POSSIBLE_DUPLICATE", null)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update(sql, lote.getId(), 3, "DUPLICATE", null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(sql, lote.getId(), 4, "INVALID", "motivo")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void numeroDeLinhaEUnicoPorImportacaoEPositivo() {
        UUID dono = novoUsuario();
        ImportBatch lote = previa(dono, novaConta(dono));
        items.saveAndFlush(linhaNova(lote, 1));

        assertThatThrownBy(() -> items.saveAndFlush(linhaNova(lote, 1))).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> items.saveAndFlush(linhaNova(lote, 0))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void descartarAPreviaApagaSuasLinhas() {
        UUID dono = novoUsuario();
        ImportBatch lote = previa(dono, novaConta(dono));
        items.saveAndFlush(linhaNova(lote, 1));
        items.saveAndFlush(linhaNova(lote, 2));

        imports.deleteById(lote.getId());
        imports.flush();

        assertThat(items.countByImportIdAndStatus(lote.getId(), ImportItemStatus.NEW)).isZero();
    }

    @Test
    void duplicataApontaParaATransacaoExistenteEOApontamentoSoltaSeElaSomeDoBanco() {
        UUID dono = novoUsuario();
        UUID conta = novaConta(dono);
        ImportBatch lote = previa(dono, conta);
        Transaction existente = transactions.saveAndFlush(new Transaction(dono, conta, TransactionType.EXPENSE, new BigDecimal("12.34"), Instant.now()));
        ImportItem dup = linhaNova(lote, 1);
        dup.setStatus(ImportItemStatus.DUPLICATE);
        dup.setSelected(false);
        dup.setDuplicateOf(existente.getId());
        items.saveAndFlush(dup);

        jdbc.update("delete from transactions where id = ?", existente.getId());

        assertThat(items.findById(dup.getId()).orElseThrow().getDuplicateOf()).isNull();
    }

    // ---------------------------------------------------------------- transações importadas

    @Test
    void transacaoImportadaGuardaImportacaoEIdentificador() {
        UUID dono = novoUsuario();
        UUID conta = novaConta(dono);
        ImportBatch lote = confirmada(dono, conta);

        Transaction salva = transactions.saveAndFlush(importada(dono, conta, lote, "FIT-001"));

        Transaction lida = transactions.findById(salva.getId()).orElseThrow();
        assertThat(lida.getImportId()).isEqualTo(lote.getId());
        assertThat(lida.getExternalId()).isEqualTo("FIT-001");
        assertThat(lida.getSource()).isEqualTo(TransactionSource.OFX);
    }

    @Test
    void transacaoSoPodeApontarParaImportacaoDaPropriaConta() {
        UUID dono = novoUsuario();
        UUID conta = novaConta(dono);
        UUID outraConta = novaConta(dono);
        ImportBatch loteDaConta = confirmada(dono, conta);

        // mesmo dono, mas a importação é de OUTRA conta
        assertThatThrownBy(() -> transactions.saveAndFlush(importada(dono, outraConta, loteDaConta, "FIT-X")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void transacaoNaoPodeApontarParaImportacaoDeOutroUsuario() {
        UUID ana = novoUsuario();
        UUID bia = novoUsuario();
        UUID contaDaAna = novaConta(ana);
        UUID contaDaBia = novaConta(bia);
        ImportBatch loteDaAna = confirmada(ana, contaDaAna);

        assertThatThrownBy(() -> transactions.saveAndFlush(importada(bia, contaDaBia, loteDaAna, "FIT-Y")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void lancamentoManualNaoTemImportacaoEImportacaoExigeOrigemDeArquivo() {
        UUID dono = novoUsuario();
        UUID conta = novaConta(dono);
        ImportBatch lote = confirmada(dono, conta);

        Transaction manualComImportacao = new Transaction(dono, conta, TransactionType.EXPENSE, BigDecimal.TEN, Instant.now());
        manualComImportacao.markImported(lote.getId(), null, TransactionSource.MANUAL);
        assertThatThrownBy(() -> transactions.saveAndFlush(manualComImportacao)).isInstanceOf(DataIntegrityViolationException.class);

        Transaction csv = new Transaction(dono, conta, TransactionType.INCOME, BigDecimal.TEN, Instant.now());
        csv.markImported(lote.getId(), null, TransactionSource.CSV);
        assertThat(transactions.saveAndFlush(csv).getId()).isNotNull();
    }

    @Test
    void identificadorExternoNaoSeRepeteNaMesmaContaEntreAsAtivas() {
        UUID dono = novoUsuario();
        UUID conta = novaConta(dono);
        UUID outraConta = novaConta(dono);
        ImportBatch lote = confirmada(dono, conta);
        ImportBatch loteOutra = confirmada(dono, outraConta);

        transactions.saveAndFlush(importada(dono, conta, lote, "FIT-DUP"));
        assertThatThrownBy(() -> transactions.saveAndFlush(importada(dono, conta, lote, "FIT-DUP")))
                .isInstanceOf(DataIntegrityViolationException.class);
        // outra conta pode ter o mesmo identificador (cada banco tem a sua numeração)
        assertThat(transactions.saveAndFlush(importada(dono, outraConta, loteOutra, "FIT-DUP")).getId()).isNotNull();
    }

    @Test
    void semIdentificadorExternoNaoHaRestricaoDeUnicidade() {
        UUID dono = novoUsuario();
        UUID conta = novaConta(dono);
        ImportBatch lote = confirmada(dono, conta);

        transactions.saveAndFlush(importada(dono, conta, lote, null));
        transactions.saveAndFlush(importada(dono, conta, lote, null));
        transactions.saveAndFlush(new Transaction(dono, conta, TransactionType.EXPENSE, BigDecimal.ONE, Instant.now()));
    }

    @Test
    void exclusaoLogicaLiberaOIdentificadorParaReimportar() {
        UUID dono = novoUsuario();
        UUID conta = novaConta(dono);
        ImportBatch lote = confirmada(dono, conta);
        Transaction primeira = transactions.saveAndFlush(importada(dono, conta, lote, "FIT-RE"));

        primeira.setDeletedAt(Instant.now());   // o que o rollback faz
        transactions.saveAndFlush(primeira);

        assertThat(transactions.saveAndFlush(importada(dono, conta, lote, "FIT-RE")).getId()).isNotNull();
    }

    @Test
    void importacaoComTransacoesNaoPodeSerApagadaMasUmaPreviaPode() {
        UUID dono = novoUsuario();
        UUID conta = novaConta(dono);
        ImportBatch lote = confirmada(dono, conta);
        transactions.saveAndFlush(importada(dono, conta, lote, "FIT-1"));

        // o histórico de uma importação confirmada é preservado: só o rollback lógico a desfaz
        assertThatThrownBy(() -> jdbc.update("delete from imports where id = ?", lote.getId())).isInstanceOf(DataIntegrityViolationException.class);

        ImportBatch previa = previa(dono, conta);
        assertThat(jdbc.update("delete from imports where id = ?", previa.getId())).isEqualTo(1);
    }

    @Test
    void apagarOUsuarioRemoveImportacoesLinhasETransacoes() {
        UUID dono = novoUsuario();
        UUID conta = novaConta(dono);
        ImportBatch lote = confirmada(dono, conta);
        items.saveAndFlush(linhaNova(lote, 1));
        transactions.saveAndFlush(importada(dono, conta, lote, "FIT-U"));

        jdbc.update("delete from users where id = ?", dono);   // direito ao apagamento (LGPD)

        assertThat(jdbc.queryForObject("select count(*) from imports where user_id = ?", Integer.class, dono)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from import_items where import_id = ?", Integer.class, lote.getId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from transactions where user_id = ?", Integer.class, dono)).isZero();
    }

    @Test
    void migracaoV6FoiAplicadaEAsTransacoesAntigasContinuamValidas() {
        Integer colunas = jdbc.queryForObject(
                "select count(*) from information_schema.columns where table_name = 'transactions' and column_name in ('import_id', 'external_id')",
                Integer.class);
        assertThat(colunas).isEqualTo(2);
        // transações do M2 (sem importação) continuam com os dois campos nulos
        UUID dono = novoUsuario();
        Transaction t = transactions.saveAndFlush(new Transaction(dono, novaConta(dono), TransactionType.INCOME, BigDecimal.ONE, Instant.now()));
        assertThat(t.getImportId()).isNull();
        assertThat(t.getExternalId()).isNull();
    }
}
