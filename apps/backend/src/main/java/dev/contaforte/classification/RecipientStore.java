package dev.contaforte.classification;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Favorecidos PIX aprendidos e os votos por direção (V7/V8). SQL direto: os upserts precisam ser atômicos. Tudo é
 * escopado ao usuário. O nome de uma pessoa é dado de terceiro (ADR-018): nunca vai a log nem a mensagem de erro.
 */
@Repository
class RecipientStore {

    record Row(UUID id, String normalizedName, String displayName, int transactionCount, BigDecimal averageAmount, Instant lastTransactionAt) {
    }

    /** Votos de um favorecido: direção → (categoria → votos) e (contexto → votos). */
    record Votes(Map<String, Map<UUID, Integer>> categories, Map<String, Map<UUID, Integer>> contexts) {
        Votes() {
            this(new HashMap<>(), new HashMap<>());
        }

        RecipientKnowledge knowledge(String direction) {
            return new RecipientKnowledge(categories.getOrDefault(direction, Map.of()), contexts.getOrDefault(direction, Map.of()));
        }
    }

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    RecipientStore(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    /** Tudo o que se aprendeu do usuário, por "nome normalizado|direção": uma passada só, para classificar muitas linhas. */
    Map<String, RecipientKnowledge> knowledgeOf(UUID userId) {
        Map<String, Map<UUID, Integer>> cats = new HashMap<>();
        Map<String, Map<UUID, Integer>> ctxs = new HashMap<>();
        jdbc.query("select r.normalized_name, v.direction, v.category_id, v.votes from pix_recipients r "
                + "join pix_recipient_category_votes v on v.recipient_id = r.id where r.user_id = ?", rs -> {
            cats.computeIfAbsent(rs.getString(1) + "|" + rs.getString(2), k -> new HashMap<>()).put(rs.getObject(3, UUID.class), rs.getInt(4));
        }, userId);
        jdbc.query("select r.normalized_name, v.direction, v.context_id, v.votes from pix_recipients r "
                + "join pix_recipient_context_votes v on v.recipient_id = r.id where r.user_id = ?", rs -> {
            ctxs.computeIfAbsent(rs.getString(1) + "|" + rs.getString(2), k -> new HashMap<>()).put(rs.getObject(3, UUID.class), rs.getInt(4));
        }, userId);
        Map<String, RecipientKnowledge> out = new HashMap<>();
        for (String key : cats.keySet()) out.put(key, new RecipientKnowledge(cats.get(key), ctxs.getOrDefault(key, Map.of())));
        for (String key : ctxs.keySet()) out.putIfAbsent(key, new RecipientKnowledge(Map.of(), ctxs.get(key)));
        return out;
    }

    /** Soma uma decisão do usuário: cria o favorecido se preciso, atualiza as estatísticas e soma os votos. */
    void learn(UUID userId, CounterpartyName.Result who, String direction, BigDecimal amount, Instant when, UUID category, UUID context) {
        UUID id = jdbc.queryForObject("insert into pix_recipients (user_id, normalized_name, display_name, transaction_count, average_amount, last_transaction_at) "
                + "values (?, ?, ?, 1, ?, ?) on conflict (user_id, normalized_name) do update set "
                + "transaction_count = pix_recipients.transaction_count + 1, "
                + "average_amount = round((coalesce(pix_recipients.average_amount, 0) * pix_recipients.transaction_count + excluded.average_amount) "
                + "  / (pix_recipients.transaction_count + 1), 2), "
                + "last_transaction_at = greatest(pix_recipients.last_transaction_at, excluded.last_transaction_at), updated_at = now() returning id",
                UUID.class, userId, who.key(), who.display(), amount, Timestamp.from(when));
        if (category != null) {
            jdbc.update("insert into pix_recipient_category_votes (recipient_id, direction, category_id, votes) values (?, ?, ?, 1) "
                    + "on conflict (recipient_id, direction, category_id) do update set votes = pix_recipient_category_votes.votes + 1", id, direction, category);
        }
        if (context != null) {
            jdbc.update("insert into pix_recipient_context_votes (recipient_id, direction, context_id, votes) values (?, ?, ?, 1) "
                    + "on conflict (recipient_id, direction, context_id) do update set votes = pix_recipient_context_votes.votes + 1", id, direction, context);
        }
    }

    /** Retira um voto (desfaz o aprendizado); o favorecido some quando não sobra nada dele. */
    void unlearn(UUID userId, String normalizedName, String direction, UUID category, UUID context) {
        List<UUID> ids = jdbc.queryForList("select id from pix_recipients where user_id = ? and normalized_name = ?", UUID.class, userId, normalizedName);
        if (ids.isEmpty()) return;
        UUID id = ids.get(0);
        if (category != null) {
            jdbc.update("delete from pix_recipient_category_votes where recipient_id = ? and direction = ? and category_id = ? and votes <= 1", id, direction, category);
            jdbc.update("update pix_recipient_category_votes set votes = votes - 1 where recipient_id = ? and direction = ? and category_id = ? and votes > 1", id, direction, category);
        }
        if (context != null) {
            jdbc.update("delete from pix_recipient_context_votes where recipient_id = ? and direction = ? and context_id = ? and votes <= 1", id, direction, context);
            jdbc.update("update pix_recipient_context_votes set votes = votes - 1 where recipient_id = ? and direction = ? and context_id = ? and votes > 1", id, direction, context);
        }
        jdbc.update("update pix_recipients set transaction_count = greatest(transaction_count - 1, 0), updated_at = now() where id = ?", id);
        jdbc.update("delete from pix_recipients r where r.id = ? and r.transaction_count = 0 "
                + "and not exists (select 1 from pix_recipient_category_votes where recipient_id = r.id) "
                + "and not exists (select 1 from pix_recipient_context_votes where recipient_id = r.id)", id);
    }

    Optional<Row> find(UUID userId, UUID id) {
        return jdbc.query("select id, normalized_name, display_name, transaction_count, average_amount, last_transaction_at from pix_recipients where id = ? and user_id = ?",
                (rs, n) -> row(rs), id, userId).stream().findFirst();
    }

    long count(UUID userId, String likePattern) {
        Long n = jdbc.queryForObject("select count(*) from pix_recipients where user_id = ? and normalized_name like ? escape '\\'", Long.class, userId, likePattern);
        return n == null ? 0 : n;
    }

    List<Row> list(UUID userId, String likePattern, int limit, long offset) {
        return jdbc.query("select id, normalized_name, display_name, transaction_count, average_amount, last_transaction_at from pix_recipients "
                + "where user_id = ? and normalized_name like ? escape '\\' "
                + "order by last_transaction_at desc nulls last, normalized_name, id limit ? offset ?", (rs, n) -> row(rs), userId, likePattern, limit, offset);
    }

    /** Apaga o favorecido e os votos dele (esquecer, ADR-018). Devolve se existia. */
    boolean delete(UUID userId, UUID id) {
        return jdbc.update("delete from pix_recipients where id = ? and user_id = ?", id, userId) > 0;
    }

    Map<UUID, Votes> votesOf(List<UUID> recipientIds) {
        Map<UUID, Votes> out = new HashMap<>();
        if (recipientIds.isEmpty()) return out;
        MapSqlParameterSource p = new MapSqlParameterSource("ids", recipientIds);
        named.query("select recipient_id, direction, category_id, votes from pix_recipient_category_votes where recipient_id in (:ids)", p, rs -> {
            out.computeIfAbsent(rs.getObject(1, UUID.class), k -> new Votes()).categories()
                    .computeIfAbsent(rs.getString(2), k -> new HashMap<>()).put(rs.getObject(3, UUID.class), rs.getInt(4));
        });
        named.query("select recipient_id, direction, context_id, votes from pix_recipient_context_votes where recipient_id in (:ids)", p, rs -> {
            out.computeIfAbsent(rs.getObject(1, UUID.class), k -> new Votes()).contexts()
                    .computeIfAbsent(rs.getString(2), k -> new HashMap<>()).put(rs.getObject(3, UUID.class), rs.getInt(4));
        });
        return out;
    }

    void insertFeedback(UUID userId, UUID transactionId, String kind, UUID prevCategory, UUID newCategory, UUID prevContext, UUID newContext,
                        String predictionSource, BigDecimal predictionConfidence) {
        jdbc.update("insert into classification_feedback (user_id, transaction_id, kind, previous_category_id, corrected_category_id, "
                + "previous_context_id, corrected_context_id, prediction_source, prediction_confidence, model_version) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                userId, transactionId, kind, prevCategory, newCategory, prevContext, newContext, predictionSource, predictionConfidence, ClassificationEngine.VERSION);
    }

    /** Marca o feedback das transações como revertido (o registro permanece, para o histórico). */
    void markFeedbackReverted(UUID userId, List<UUID> transactionIds) {
        if (transactionIds.isEmpty()) return;
        named.update("update classification_feedback set reverted_at = now() where user_id = :user and transaction_id in (:ids) and reverted_at is null",
                new MapSqlParameterSource("user", userId).addValue("ids", transactionIds));
    }

    private static Row row(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp last = rs.getTimestamp(6);
        return new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getBigDecimal(5), last == null ? null : last.toInstant());
    }

    /** Padrão LIKE para "contém": escapa % _ e a própria barra, para a busca ser literal. */
    static String containsPattern(String normalizedQuery) {
        if (normalizedQuery == null || normalizedQuery.isEmpty()) return "%";
        return "%" + normalizedQuery.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
