package dev.contaforte.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.contaforte.AbstractIntegrationTest;
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

class AccountRepositoryTest extends AbstractIntegrationTest {

    @Autowired AccountRepository accounts;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;

    private UUID novoUsuario() {
        return users.saveAndFlush(new User(UUID.randomUUID() + "@exemplo.com", "hash", Role.USER)).getId();
    }

    @Test
    void persisteOsOitoTiposComSaldoDeAberturaExato() {
        UUID dono = novoUsuario();
        for (AccountType tipo : AccountType.values()) {
            Account salva = accounts.saveAndFlush(new Account(dono, "Conta " + tipo, tipo, new BigDecimal("1234.50")));
            assertThat(salva.getId()).isNotNull();
            assertThat(salva.getCurrency()).isEqualTo("BRL");
            assertThat(salva.getCreatedAt()).isNotNull();
        }
        assertThat(accounts.findByUserIdOrderByNameAsc(dono)).hasSize(8)
                .allSatisfy(a -> assertThat(a.getOpeningBalance()).isEqualByComparingTo("1234.50"));
    }

    @Test
    void tipoInvalidoEhRejeitadoPeloBanco() {
        UUID dono = novoUsuario();
        assertThatThrownBy(() -> jdbc.update(
                "insert into accounts (user_id, name, type) values (?, 'X', 'POUPANCA_MAGICA')", dono))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void moedaDiferenteDeBrlEhRejeitada() {
        UUID dono = novoUsuario();
        assertThatThrownBy(() -> jdbc.update(
                "insert into accounts (user_id, name, type, currency) values (?, 'Dólar', 'CASH', 'USD')", dono))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void nomeDuplicadoNoMesmoUsuarioEhRejeitadoSemDiferenciarCaixa() {
        UUID dono = novoUsuario();
        accounts.saveAndFlush(new Account(dono, "Nubank", AccountType.CHECKING_ACCOUNT, BigDecimal.ZERO));

        assertThatThrownBy(() -> accounts.saveAndFlush(
                new Account(dono, "NUBANK", AccountType.SAVINGS_ACCOUNT, BigDecimal.ZERO)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void mesmoNomeEmUsuariosDiferentesEhPermitido() {
        accounts.saveAndFlush(new Account(novoUsuario(), "Carteira", AccountType.CASH, BigDecimal.ZERO));
        accounts.saveAndFlush(new Account(novoUsuario(), "Carteira", AccountType.CASH, BigDecimal.ZERO));
    }

    @Test
    void arquivarLiberaONome() {
        UUID dono = novoUsuario();
        Account antiga = accounts.saveAndFlush(new Account(dono, "Itaú", AccountType.CHECKING_ACCOUNT, BigDecimal.ZERO));
        antiga.setArchivedAt(Instant.now());
        accounts.saveAndFlush(antiga);

        Account nova = accounts.saveAndFlush(new Account(dono, "Itaú", AccountType.CHECKING_ACCOUNT, BigDecimal.ZERO));
        assertThat(nova.isArchived()).isFalse();
    }

    @Test
    void buscaEscopadaNaoEnxergaContaDeOutroUsuario() {
        UUID ana = novoUsuario();
        UUID bia = novoUsuario();
        Account daAna = accounts.saveAndFlush(new Account(ana, "Reserva", AccountType.SAVINGS_ACCOUNT, BigDecimal.TEN));

        assertThat(accounts.findByIdAndUserId(daAna.getId(), ana)).isPresent();
        assertThat(accounts.findByIdAndUserId(daAna.getId(), bia)).isEmpty();
        assertThat(accounts.findByUserIdOrderByNameAsc(bia)).isEmpty();
    }
}
