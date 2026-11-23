package dev.contaforte.categories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.contaforte.AbstractIntegrationTest;
import dev.contaforte.users.Role;
import dev.contaforte.users.User;
import dev.contaforte.users.UserRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class CategoryModelTest extends AbstractIntegrationTest {

    @Autowired CategoryRepository categories;
    @Autowired ContextRepository contexts;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;

    private UUID novoUsuario() {
        return users.saveAndFlush(new User(UUID.randomUUID() + "@exemplo.com", "hash", Role.USER)).getId();
    }

    @Test
    void seedTrazAsCategoriasPadraoEmDoisNiveis() {
        TransactionCategory alimentacao = categories.findByCodeAndUserIdIsNull("FOOD").orElseThrow();
        TransactionCategory restaurante = categories.findByCodeAndUserIdIsNull("FOOD_RESTAURANT").orElseThrow();

        assertThat(alimentacao.getName()).isEqualTo("Alimentação");
        assertThat(alimentacao.getParentId()).isNull();
        assertThat(alimentacao.isDefault()).isTrue();
        assertThat(restaurante.getParentId()).isEqualTo(alimentacao.getId());

        Integer filhas = jdbc.queryForObject(
                "select count(*) from transaction_categories where parent_id is not null and user_id is null",
                Integer.class);
        assertThat(filhas).isEqualTo(39);
    }

    @Test
    void seedTrazOsSeisContextosPadrao() {
        List<String> codigos = jdbc.queryForList(
                "select code from transaction_contexts where user_id is null", String.class);
        assertThat(codigos).containsExactlyInAnyOrder(
                "INDIVIDUAL", "FAMILY", "RELATIONSHIP", "PET", "WORK", "OTHER");
    }

    @Test
    void usuarioVePadraoEAsSuasESoElas() {
        UUID ana = novoUsuario();
        UUID bia = novoUsuario();
        TransactionCategory propria = categories.saveAndFlush(new TransactionCategory(ana, null, "Presentes"));

        assertThat(categories.findVisibleTo(ana)).extracting(TransactionCategory::getName).contains("Alimentação", "Presentes");
        assertThat(categories.findVisibleTo(bia)).extracting(TransactionCategory::getName)
                .contains("Alimentação").doesNotContain("Presentes");
        assertThat(categories.findVisibleById(propria.getId(), bia)).isEmpty();
        assertThat(categories.findVisibleById(propria.getId(), ana)).isPresent();
    }

    @Test
    void subcategoriaPropriaSobPaiPadraoEhPermitida() {
        UUID ana = novoUsuario();
        UUID alimentacao = categories.findByCodeAndUserIdIsNull("FOOD").orElseThrow().getId();

        TransactionCategory sub = categories.saveAndFlush(new TransactionCategory(ana, alimentacao, "Cafeteria"));
        assertThat(sub.getParentId()).isEqualTo(alimentacao);
    }

    @Test
    void terceiroNivelEhRejeitado() {
        UUID ana = novoUsuario();
        UUID restaurante = categories.findByCodeAndUserIdIsNull("FOOD_RESTAURANT").orElseThrow().getId();

        assertThatThrownBy(() -> categories.saveAndFlush(new TransactionCategory(ana, restaurante, "Japonês")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("2 níveis");
    }

    @Test
    void naoSePodeCriarSubcategoriaSobPaiDeOutroUsuario() {
        UUID ana = novoUsuario();
        UUID bia = novoUsuario();
        TransactionCategory paiDaAna = categories.saveAndFlush(new TransactionCategory(ana, null, "Hobbies"));

        assertThatThrownBy(() -> categories.saveAndFlush(new TransactionCategory(bia, paiDaAna.getId(), "Pesca")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("outro usuário");
    }

    @Test
    void nomeDuplicadoSobOMesmoPaiEhRejeitadoMasEmPaisDiferentesNao() {
        UUID ana = novoUsuario();
        categories.saveAndFlush(new TransactionCategory(ana, null, "Pets"));
        assertThatThrownBy(() -> categories.saveAndFlush(new TransactionCategory(ana, null, "PETS")))
                .isInstanceOf(DataIntegrityViolationException.class);

        UUID saude = categories.findByCodeAndUserIdIsNull("HEALTH").orElseThrow().getId();
        UUID lazer = categories.findByCodeAndUserIdIsNull("LEISURE").orElseThrow().getId();
        categories.saveAndFlush(new TransactionCategory(ana, saude, "Outros"));
        categories.saveAndFlush(new TransactionCategory(ana, lazer, "Outros"));
    }

    @Test
    void usuarioNaoPodeTerCategoriaComCodigoNemPadraoSemCodigo() {
        UUID ana = novoUsuario();
        assertThatThrownBy(() -> jdbc.update(
                "insert into transaction_categories (user_id, code, name) values (?, 'FAKE', 'X')", ana))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into transaction_categories (name) values ('Sem código')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void contextosSeguemAsMesmasRegrasDeVisibilidade() {
        UUID ana = novoUsuario();
        UUID bia = novoUsuario();
        TransactionContext avos = contexts.saveAndFlush(new TransactionContext(ana, "Avós"));

        assertThat(contexts.findVisibleTo(ana)).extracting(TransactionContext::getName).contains("Família", "Avós");
        assertThat(contexts.findVisibleTo(bia)).extracting(TransactionContext::getName).doesNotContain("Avós");
        assertThat(contexts.findVisibleById(avos.getId(), bia)).isEmpty();
        assertThatThrownBy(() -> contexts.saveAndFlush(new TransactionContext(ana, "avós")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
