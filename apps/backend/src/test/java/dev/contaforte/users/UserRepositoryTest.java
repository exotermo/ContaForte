package dev.contaforte.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.contaforte.PostgresTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(PostgresTestConfig.class)
class UserRepositoryTest {

    @Autowired UserRepository repository;
    @Autowired JdbcTemplate jdbc;

    @Test
    void migracaoV1CriaTabelasEsperadas() {
        Integer tabelas = jdbc.queryForObject(
                "select count(*) from information_schema.tables "
                        + "where table_schema = 'public' and table_name in ('users', 'audit_logs')",
                Integer.class);
        assertThat(tabelas).isEqualTo(2);

        String versao = jdbc.queryForObject(
                "select max(version) from flyway_schema_history where success", String.class);
        assertThat(versao).isEqualTo("1");
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
        repository.saveAndFlush(new User("Bia@Exemplo.com", "hash", Role.ADMIN));

        assertThat(repository.findByEmailIgnoreCase("bia@exemplo.COM"))
                .hasValueSatisfying(u -> assertThat(u.getRole()).isEqualTo(Role.ADMIN));
    }

    @Test
    void emailDuplicadoSemDiferenciarCaixaEhRejeitado() {
        repository.saveAndFlush(new User("caio@exemplo.com", "hash", Role.USER));

        assertThatThrownBy(() -> repository.saveAndFlush(new User("CAIO@exemplo.com", "hash", Role.USER)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
