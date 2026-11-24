package dev.contaforte.classification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.contaforte.AbstractIntegrationTest;
import dev.contaforte.users.Role;
import dev.contaforte.users.User;
import dev.contaforte.users.UserRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Restrições da V7 exercitadas direto no banco: o que a API venha a fazer errado, o banco recusa (ADR-017, ADR-018). */
class ClassificationModelTest extends AbstractIntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;

    private UUID usuario() {
        return users.saveAndFlush(new User(UUID.randomUUID() + "@exemplo.com", "hash", Role.USER)).getId();
    }

    private UUID conta(UUID dono) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into accounts (id, user_id, name, type, opening_balance) values (?, ?, ?, 'CHECKING_ACCOUNT', 0)", id, dono, "Conta " + id);
        return id;
    }

    private UUID transacao(UUID dono, UUID conta) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into transactions (id, user_id, account_id, type, amount, occurred_at) values (?, ?, ?, 'EXPENSE', 10.00, now())", id, dono, conta);
        return id;
    }

    private UUID categoriaPadrao() {
        return jdbc.queryForObject("select id from transaction_categories where code = 'FOOD_GROCERIES'", UUID.class);
    }

    private UUID categoriaDe(UUID dono) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into transaction_categories (id, user_id, name) values (?, ?, ?)", id, dono, "Minha " + id);
        return id;
    }

    private UUID contextoDe(UUID dono) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into transaction_contexts (id, user_id, name) values (?, ?, ?)", id, dono, "Meu " + id);
        return id;
    }

    private UUID favorecido(UUID dono, String chave) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into pix_recipients (id, user_id, normalized_name, display_name) values (?, ?, ?, ?)", id, dono, chave, chave);
        return id;
    }

    // ---------------------------------------------------------------- estado da transação

    @Test
    void transacaoNovaNasceSemClassificacao() {
        UUID dono = usuario();
        UUID t = transacao(dono, conta(dono));

        assertThat(jdbc.queryForObject("select classification_status from transactions where id = ?", String.class, t)).isEqualTo("NONE");
        assertThat(jdbc.queryForObject("select classification_source from transactions where id = ?", String.class, t)).isNull();
    }

    @Test
    void combinacoesValidasDeEstadoSaoAceitas() {
        UUID dono = usuario();
        UUID c = conta(dono);
        UUID cat = categoriaPadrao();
        UUID a = transacao(dono, c);
        UUID s = transacao(dono, c);
        UUID u = transacao(dono, c);

        jdbc.update("update transactions set classification_status = 'AUTO', classification_source = 'RECIPIENT', classification_confidence = 0.90, category_id = ? where id = ?", cat, a);
        jdbc.update("update transactions set classification_status = 'SUGGESTED', classification_source = 'DEFAULT_RULE', classification_confidence = 0.60, suggested_category_id = ? where id = ?", cat, s);
        jdbc.update("update transactions set classification_status = 'CONFIRMED', classification_source = 'USER', category_id = ? where id = ?", cat, u);

        assertThat(jdbc.queryForObject("select count(*) from transactions where id in (?, ?, ?) and classification_status <> 'NONE'", Integer.class, a, s, u)).isEqualTo(3);
    }

    @Test
    void estadoInvalidoOuSemOrigemEhRecusado() {
        UUID dono = usuario();
        UUID t = transacao(dono, conta(dono));
        UUID cat = categoriaPadrao();

        assertThatThrownBy(() -> jdbc.update("update transactions set classification_status = 'TALVEZ' where id = ?", t)).isInstanceOf(DataIntegrityViolationException.class);
        // NONE não pode ter origem
        assertThatThrownBy(() -> jdbc.update("update transactions set classification_source = 'RULE', classification_confidence = 0.9 where id = ?", t)).isInstanceOf(DataIntegrityViolationException.class);
        // AUTO exige origem e confiança
        assertThatThrownBy(() -> jdbc.update("update transactions set classification_status = 'AUTO', category_id = ? where id = ?", cat, t)).isInstanceOf(DataIntegrityViolationException.class);
        // AUTO e SUGGESTED nunca "vêm do usuário"
        assertThatThrownBy(() -> jdbc.update("update transactions set classification_status = 'AUTO', classification_source = 'USER', classification_confidence = 1 where id = ?", t)).isInstanceOf(DataIntegrityViolationException.class);
        // CONFIRMED vem do usuário
        assertThatThrownBy(() -> jdbc.update("update transactions set classification_status = 'CONFIRMED', classification_source = 'RULE', classification_confidence = 1 where id = ?", t)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void confiancaForaDeZeroAUmEhRecusada() {
        UUID dono = usuario();
        UUID t = transacao(dono, conta(dono));

        assertThatThrownBy(() -> jdbc.update("update transactions set classification_status = 'AUTO', classification_source = 'RULE', classification_confidence = 1.01 where id = ?", t))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update transactions set classification_status = 'AUTO', classification_source = 'RULE', classification_confidence = -0.01 where id = ?", t))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sugestaoSoExisteNoEstadoSugeridoENaoCobreCampoJaPreenchido() {
        UUID dono = usuario();
        UUID c = conta(dono);
        UUID cat = categoriaPadrao();
        UUID t = transacao(dono, c);

        // sugestão sem o estado SUGGESTED
        assertThatThrownBy(() -> jdbc.update("update transactions set suggested_category_id = ? where id = ?", cat, t)).isInstanceOf(DataIntegrityViolationException.class);
        // SUGGESTED sem nenhuma sugestão
        assertThatThrownBy(() -> jdbc.update("update transactions set classification_status = 'SUGGESTED', classification_source = 'RULE', classification_confidence = 0.6 where id = ?", t))
                .isInstanceOf(DataIntegrityViolationException.class);
        // sugerir categoria quando a categoria já está preenchida
        assertThatThrownBy(() -> jdbc.update("update transactions set category_id = ?, classification_status = 'SUGGESTED', classification_source = 'RULE', classification_confidence = 0.6, suggested_category_id = ? where id = ?", cat, cat, t))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void categoriaSugeridaDeOutroUsuarioEhRecusada() {
        UUID ana = usuario();
        UUID bia = usuario();
        UUID t = transacao(ana, conta(ana));
        UUID daBia = categoriaDe(bia);

        assertThatThrownBy(() -> jdbc.update("update transactions set classification_status = 'SUGGESTED', classification_source = 'RULE', classification_confidence = 0.6, suggested_category_id = ? where id = ?", daBia, t))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---------------------------------------------------------------- favorecidos

    @Test
    void favorecidoEhUnicoPorUsuarioENaoPorSistema() {
        UUID ana = usuario();
        UUID bia = usuario();
        favorecido(ana, "joana exemplo");

        favorecido(bia, "joana exemplo");   // outro usuário pode ter o mesmo nome
        assertThatThrownBy(() -> favorecido(ana, "joana exemplo")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void favorecidoRecusaNomeCurtoContagemNegativaECategoriaDeOutroUsuario() {
        UUID ana = usuario();
        UUID bia = usuario();
        assertThatThrownBy(() -> favorecido(ana, "ze")).isInstanceOf(DataIntegrityViolationException.class);

        UUID f = favorecido(ana, "joana exemplo");
        assertThatThrownBy(() -> jdbc.update("update pix_recipients set transaction_count = -1 where id = ?", f)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update pix_recipients set pinned_category_id = ? where id = ?", categoriaDe(bia), f)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update pix_recipients set pinned_context_id = ? where id = ?", contextoDe(bia), f)).isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("update pix_recipients set pinned_category_id = ?, pinned_context_id = ? where id = ?", categoriaPadrao(), contextoDe(ana), f);   // o próprio dono e as padrão valem
    }

    @Test
    void votosSaoPositivosUnicosPorCategoriaEDoMesmoDono() {
        UUID ana = usuario();
        UUID bia = usuario();
        UUID f = favorecido(ana, "joana exemplo");
        UUID cat = categoriaPadrao();

        jdbc.update("insert into pix_recipient_category_votes (recipient_id, category_id, votes) values (?, ?, 2)", f, cat);
        assertThatThrownBy(() -> jdbc.update("insert into pix_recipient_category_votes (recipient_id, category_id, votes) values (?, ?, 1)", f, cat)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into pix_recipient_category_votes (recipient_id, category_id, votes) values (?, ?, 0)", f, categoriaDe(ana))).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into pix_recipient_category_votes (recipient_id, category_id, votes) values (?, ?, 1)", f, categoriaDe(bia))).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into pix_recipient_context_votes (recipient_id, context_id, votes) values (?, ?, 1)", f, contextoDe(bia))).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("insert into pix_recipient_context_votes (recipient_id, context_id, votes) values (?, ?, 3)", f, contextoDe(ana));
    }

    @Test
    void apagarFavorecidoApagaOsVotosEApagarOUsuarioApagaTudo() {
        UUID ana = usuario();
        UUID f = favorecido(ana, "joana exemplo");
        jdbc.update("insert into pix_recipient_category_votes (recipient_id, category_id, votes) values (?, ?, 2)", f, categoriaPadrao());
        UUID c = conta(ana);
        UUID t = transacao(ana, c);
        jdbc.update("insert into categorization_rules (user_id, match_type, pattern, category_id) values (?, 'CONTAINS', 'mercado', ?)", ana, categoriaPadrao());
        jdbc.update("insert into classification_feedback (user_id, transaction_id, kind, corrected_category_id, model_version) values (?, ?, 'CONFIRMED', ?, 'regras-1')", ana, t, categoriaPadrao());

        jdbc.update("delete from pix_recipients where id = ?", f);
        assertThat(jdbc.queryForObject("select count(*) from pix_recipient_category_votes where recipient_id = ?", Integer.class, f)).isZero();

        jdbc.update("delete from transactions where user_id = ?", ana);          // o feedback acompanha a transação
        assertThat(jdbc.queryForObject("select count(*) from classification_feedback where user_id = ?", Integer.class, ana)).isZero();
        jdbc.update("delete from users where id = ?", ana);
        assertThat(jdbc.queryForObject("select count(*) from categorization_rules where user_id = ?", Integer.class, ana)).isZero();
    }

    // ---------------------------------------------------------------- regras

    @Test
    void regraPrecisaDeAlvoPadraoValidoECondicaoUnica() {
        UUID ana = usuario();
        UUID cat = categoriaPadrao();
        String sql = "insert into categorization_rules (user_id, match_type, pattern, transaction_type, category_id, context_id, priority) values (?, ?, ?, ?, ?, ?, ?)";

        jdbc.update(sql, ana, "CONTAINS", "mercado", null, cat, null, 100);
        assertThatThrownBy(() -> jdbc.update(sql, ana, "CONTAINS", "mercado", null, cat, null, 5)).isInstanceOf(DataIntegrityViolationException.class);   // mesma condição
        jdbc.update(sql, ana, "CONTAINS", "mercado", "EXPENSE", cat, null, 100);                                                                     // tipo diferente é outra condição
        jdbc.update(sql, ana, "STARTS_WITH", "mercado", null, cat, null, 100);
        assertThatThrownBy(() -> jdbc.update(sql, ana, "CONTAINS", "farmacia", null, null, null, 100)).isInstanceOf(DataIntegrityViolationException.class);  // sem alvo
        assertThatThrownBy(() -> jdbc.update(sql, ana, "REGEX", "farmacia", null, cat, null, 100)).isInstanceOf(DataIntegrityViolationException.class);     // só 3 tipos de comparação
        assertThatThrownBy(() -> jdbc.update(sql, ana, "CONTAINS", "ab", null, cat, null, 100)).isInstanceOf(DataIntegrityViolationException.class);         // padrão curto
        assertThatThrownBy(() -> jdbc.update(sql, ana, "CONTAINS", "posto", "TRANSFER", cat, null, 100)).isInstanceOf(DataIntegrityViolationException.class); // só receita e despesa
        assertThatThrownBy(() -> jdbc.update(sql, ana, "CONTAINS", "posto", null, cat, null, 10001)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void regraComCategoriaOuContextoDeOutroUsuarioEhRecusada() {
        UUID ana = usuario();
        UUID bia = usuario();
        String sql = "insert into categorization_rules (user_id, match_type, pattern, category_id, context_id) values (?, 'CONTAINS', 'posto', ?, ?)";

        assertThatThrownBy(() -> jdbc.update(sql, ana, categoriaDe(bia), null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(sql, ana, null, contextoDe(bia))).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(sql, ana, categoriaDe(ana), contextoDe(ana));
    }

    // ---------------------------------------------------------------- feedback

    @Test
    void feedbackSoAceitaTransacaoDoMesmoUsuario() {
        UUID ana = usuario();
        UUID bia = usuario();
        UUID daAna = transacao(ana, conta(ana));
        String sql = "insert into classification_feedback (user_id, transaction_id, kind, corrected_category_id, model_version) values (?, ?, 'CONFIRMED', ?, 'regras-1')";

        jdbc.update(sql, ana, daAna, categoriaPadrao());
        assertThatThrownBy(() -> jdbc.update(sql, bia, daAna, categoriaPadrao())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void correcaoPrecisaMudarAlgoEConfirmacaoPrecisaDecidirAlgo() {
        UUID ana = usuario();
        UUID t = transacao(ana, conta(ana));
        UUID a = categoriaPadrao();
        UUID b = categoriaDe(ana);
        String sql = "insert into classification_feedback (user_id, transaction_id, kind, previous_category_id, corrected_category_id, previous_context_id, corrected_context_id, "
                + "prediction_source, prediction_confidence, model_version) values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'regras-1')";

        jdbc.update(sql, ana, t, "CORRECTED", a, b, null, null, "RECIPIENT", 0.62);
        assertThatThrownBy(() -> jdbc.update(sql, ana, t, "CORRECTED", a, a, null, null, null, null)).isInstanceOf(DataIntegrityViolationException.class);   // nada mudou
        assertThatThrownBy(() -> jdbc.update(sql, ana, t, "CONFIRMED", null, null, null, null, null, null)).isInstanceOf(DataIntegrityViolationException.class); // nada decidido
        assertThatThrownBy(() -> jdbc.update(sql, ana, t, "TALVEZ", null, a, null, null, null, null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(sql, ana, t, "CONFIRMED", null, a, null, null, "ML", 0.5)).isInstanceOf(DataIntegrityViolationException.class);   // origem desconhecida
        assertThatThrownBy(() -> jdbc.update(sql, ana, t, "CONFIRMED", null, a, null, null, "RULE", 1.5)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void feedbackComCategoriaDeOutroUsuarioEhRecusado() {
        UUID ana = usuario();
        UUID bia = usuario();
        UUID t = transacao(ana, conta(ana));

        assertThatThrownBy(() -> jdbc.update("insert into classification_feedback (user_id, transaction_id, kind, corrected_category_id, model_version) values (?, ?, 'CONFIRMED', ?, 'regras-1')",
                ana, t, categoriaDe(bia))).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into classification_feedback (user_id, transaction_id, kind, previous_context_id, corrected_context_id, model_version) values (?, ?, 'CORRECTED', ?, null, 'regras-1')",
                ana, t, contextoDe(bia))).isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---------------------------------------------------------------- migração não mexe no passado

    @Test
    void indiceDaFilaDeRevisaoExiste() {
        assertThat(jdbc.queryForObject("select count(*) from pg_indexes where indexname = 'ix_transactions_review'", Integer.class)).isEqualTo(1);
    }
}
