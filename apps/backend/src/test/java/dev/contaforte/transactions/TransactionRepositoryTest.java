package dev.contaforte.transactions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.contaforte.AbstractIntegrationTest;
import dev.contaforte.accounts.Account;
import dev.contaforte.accounts.AccountRepository;
import dev.contaforte.accounts.AccountType;
import dev.contaforte.categories.CategoryRepository;
import dev.contaforte.categories.ContextRepository;
import dev.contaforte.categories.TransactionCategory;
import dev.contaforte.categories.TransactionContext;
import dev.contaforte.users.Role;
import dev.contaforte.users.User;
import dev.contaforte.users.UserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class TransactionRepositoryTest extends AbstractIntegrationTest {

    @Autowired TransactionRepository transactions;
    @Autowired AccountRepository accounts;
    @Autowired CategoryRepository categories;
    @Autowired ContextRepository contexts;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;

    private UUID novoUsuario() {
        return users.saveAndFlush(new User(UUID.randomUUID() + "@exemplo.com", "hash", Role.USER)).getId();
    }

    private UUID novaConta(UUID dono, AccountType tipo) {
        return accounts.saveAndFlush(new Account(dono, "Conta " + UUID.randomUUID(), tipo, BigDecimal.ZERO)).getId();
    }

    private Transaction tx(UUID dono, UUID conta, TransactionType tipo, String valor) {
        return new Transaction(dono, conta, tipo, new BigDecimal(valor), Instant.now());
    }

    @Test
    void persisteComValorExatoOrigemManualETimestamps() {
        UUID dono = novoUsuario();
        UUID conta = novaConta(dono, AccountType.CHECKING_ACCOUNT);
        Transaction t = tx(dono, conta, TransactionType.EXPENSE, "1234567890.12");
        t.setDescription("Almoço");
        t.setCategoryId(categories.findByCodeAndUserIdIsNull("FOOD_RESTAURANT").orElseThrow().getId());
        t.setContextId(contexts.findByCodeAndUserIdIsNull("RELATIONSHIP").orElseThrow().getId());

        Transaction salva = transactions.saveAndFlush(t);

        assertThat(salva.getId()).isNotNull();
        assertThat(salva.getAmount()).isEqualByComparingTo("1234567890.12");
        assertThat(salva.getSource()).isEqualTo(TransactionSource.MANUAL);
        assertThat(salva.getCurrency()).isEqualTo("BRL");
        assertThat(salva.getCreatedAt()).isNotNull();
        assertThat(salva.isDeleted()).isFalse();
    }

    @Test
    void valorZeroOuNegativoEhRejeitado() {
        UUID dono = novoUsuario();
        UUID conta = novaConta(dono, AccountType.CASH);
        for (String invalido : new String[] {"0.00", "-10.00"}) {
            assertThatThrownBy(() -> transactions.saveAndFlush(tx(dono, conta, TransactionType.EXPENSE, invalido)))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Test
    void transferenciaEInvestimentoExigemContraparte() {
        UUID dono = novoUsuario();
        UUID conta = novaConta(dono, AccountType.CHECKING_ACCOUNT);
        for (TransactionType tipo : new TransactionType[] {TransactionType.TRANSFER, TransactionType.INVESTMENT}) {
            assertThatThrownBy(() -> transactions.saveAndFlush(tx(dono, conta, tipo, "10.00")))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Test
    void tiposSemContraparteNaoAceitamDestino() {
        UUID dono = novoUsuario();
        UUID conta = novaConta(dono, AccountType.CHECKING_ACCOUNT);
        UUID outra = novaConta(dono, AccountType.SAVINGS_ACCOUNT);
        for (TransactionType tipo : new TransactionType[] {
                TransactionType.INCOME, TransactionType.EXPENSE, TransactionType.REFUND}) {
            Transaction t = tx(dono, conta, tipo, "10.00");
            t.setCounterpartAccountId(outra);
            assertThatThrownBy(() -> transactions.saveAndFlush(t)).isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Test
    void contraparteNaoPodeSerAPropriaConta() {
        UUID dono = novoUsuario();
        UUID conta = novaConta(dono, AccountType.CHECKING_ACCOUNT);
        Transaction t = tx(dono, conta, TransactionType.TRANSFER, "10.00");
        t.setCounterpartAccountId(conta);

        assertThatThrownBy(() -> transactions.saveAndFlush(t)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void transferenciaEntreContasDoMesmoUsuarioFunciona() {
        UUID dono = novoUsuario();
        UUID origem = novaConta(dono, AccountType.CHECKING_ACCOUNT);
        UUID destino = novaConta(dono, AccountType.INVESTMENT_ACCOUNT);
        Transaction t = tx(dono, origem, TransactionType.INVESTMENT, "500.00");
        t.setCounterpartAccountId(destino);

        assertThat(transactions.saveAndFlush(t).getCounterpartAccountId()).isEqualTo(destino);
    }

    @Test
    void contaDeOutroUsuarioEhRejeitadaPeloBanco() {
        UUID ana = novoUsuario();
        UUID bia = novoUsuario();
        UUID contaDaAna = novaConta(ana, AccountType.CHECKING_ACCOUNT);
        UUID contaDaBia = novaConta(bia, AccountType.CHECKING_ACCOUNT);

        // lançamento da Bia na conta da Ana
        assertThatThrownBy(() -> transactions.saveAndFlush(tx(bia, contaDaAna, TransactionType.EXPENSE, "10.00")))
                .isInstanceOf(DataIntegrityViolationException.class);

        // transferência da Ana para a conta da Bia
        Transaction t = tx(ana, contaDaAna, TransactionType.TRANSFER, "10.00");
        t.setCounterpartAccountId(contaDaBia);
        assertThatThrownBy(() -> transactions.saveAndFlush(t)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void categoriaEContextoDeOutroUsuarioSaoRejeitados() {
        UUID ana = novoUsuario();
        UUID bia = novoUsuario();
        UUID contaDaBia = novaConta(bia, AccountType.CHECKING_ACCOUNT);
        TransactionCategory catDaAna = categories.saveAndFlush(new TransactionCategory(ana, null, "Segredos da Ana"));
        TransactionContext ctxDaAna = contexts.saveAndFlush(new TransactionContext(ana, "Mundo da Ana"));

        Transaction comCategoria = tx(bia, contaDaBia, TransactionType.EXPENSE, "10.00");
        comCategoria.setCategoryId(catDaAna.getId());
        assertThatThrownBy(() -> transactions.saveAndFlush(comCategoria))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("categoria");

        Transaction comContexto = tx(bia, contaDaBia, TransactionType.EXPENSE, "10.00");
        comContexto.setContextId(ctxDaAna.getId());
        assertThatThrownBy(() -> transactions.saveAndFlush(comContexto))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("contexto");
    }

    @Test
    void exclusaoLogicaEscondeDaBuscaEscopada() {
        UUID dono = novoUsuario();
        UUID outro = novoUsuario();
        Transaction t = transactions.saveAndFlush(tx(dono, novaConta(dono, AccountType.CASH), TransactionType.INCOME, "50.00"));

        assertThat(transactions.findByIdAndUserIdAndDeletedAtIsNull(t.getId(), dono)).isPresent();
        assertThat(transactions.findByIdAndUserIdAndDeletedAtIsNull(t.getId(), outro)).isEmpty();

        t.setDeletedAt(Instant.now());
        transactions.saveAndFlush(t);
        assertThat(transactions.findByIdAndUserIdAndDeletedAtIsNull(t.getId(), dono)).isEmpty();
        assertThat(transactions.findById(t.getId())).isPresent();   // a linha continua (rollback lógico do M3)
    }

    @Test
    void moedaEOrigemInvalidasSaoRejeitadas() {
        UUID dono = novoUsuario();
        UUID conta = novaConta(dono, AccountType.CASH);
        assertThatThrownBy(() -> jdbc.update(
                "insert into transactions (user_id, account_id, type, amount, currency, occurred_at) "
                        + "values (?, ?, 'EXPENSE', 1, 'USD', now())", dono, conta))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "insert into transactions (user_id, account_id, type, amount, source, occurred_at) "
                        + "values (?, ?, 'EXPENSE', 1, 'TELEPATIA', now())", dono, conta))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void apagarOUsuarioRemoveTudoDeleMasPreservaOsPadroes() {
        UUID dono = novoUsuario();
        UUID origem = novaConta(dono, AccountType.CHECKING_ACCOUNT);
        UUID destino = novaConta(dono, AccountType.SAVINGS_ACCOUNT);
        Transaction t = tx(dono, origem, TransactionType.TRANSFER, "10.00");
        t.setCounterpartAccountId(destino);
        transactions.saveAndFlush(t);
        categories.saveAndFlush(new TransactionCategory(dono, null, "Só minha"));
        contexts.saveAndFlush(new TransactionContext(dono, "Só meu"));

        jdbc.update("delete from users where id = ?", dono);   // direito ao apagamento (LGPD)

        assertThat(count("accounts", dono)).isZero();
        assertThat(count("transactions", dono)).isZero();
        assertThat(count("transaction_categories", dono)).isZero();
        assertThat(count("transaction_contexts", dono)).isZero();
        assertThat(categories.findByCodeAndUserIdIsNull("FOOD")).isPresent();
    }

    private int count(String tabela, UUID dono) {
        return jdbc.queryForObject("select count(*) from " + tabela + " where user_id = ?", Integer.class, dono);
    }
}
