package dev.contaforte.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.contaforte.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class UserRepositoryTest extends AbstractIntegrationTest {

    @Autowired UserRepository repository;
    @Autowired JdbcTemplate jdbc;

    @Test
    void migracoesCriamTabelasEsperadas() {
        Integer tabelas = jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_schema = 'public' "
                        + "and table_name in ('users', 'audit_logs', 'refresh_tokens', 'accounts', "
                        + "'transaction_categories', 'transaction_contexts', 'transactions', 'imports', 'import_items', "
                        + "'pix_recipients', 'pix_recipient_category_votes', 'pix_recipient_context_votes', "
                        + "'categorization_rules', 'classification_feedback')",
                Integer.class);
        assertThat(tabelas).isEqualTo(14);

        Integer aplicadas = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success", Integer.class);
        assertThat(aplicadas).isEqualTo(8);
    }

    @Test
    void persisteUsuarioComUuidETimestamps() {
        User salvo = repository.saveAndFlush(new User("ana@exemplo.com", "hash", Role.USER));

        assertThat(salvo.getId()).isNotNull();
        assertThat(salvo.getCreatedAt()).isNotNull();
        assertThat(salvo.getUpdatedAt()).isNotNull();
        assertThat(salvo.isEnabled()).isTrue();
    }

    @Test
    void buscaPorEmailIgnoraMaiusculas() {
        repository.saveAndFlush(new User("Bia@Exemplo.com", "hash", Role.USER));

        assertThat(repository.findByEmailIgnoreCase("bia@exemplo.COM")).isPresent();
        assertThat(repository.existsByEmailIgnoreCase("BIA@exemplo.com")).isTrue();
    }

    @Test
    void emailDuplicadoSemDiferenciarCaixaEhRejeitado() {
        repository.saveAndFlush(new User("caio@exemplo.com", "hash", Role.USER));

        assertThatThrownBy(() -> repository.saveAndFlush(new User("CAIO@exemplo.com", "hash", Role.USER)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
