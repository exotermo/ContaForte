package dev.contaforte.accounts;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Efeito das transações no saldo de cada conta (o saldo nunca é armazenado, ADR-013). Feito numa única consulta
 * agregada no banco. O sinal por tipo espelha {@code TransactionType.accountSign()}; um teste confere os dois.
 */
@Component
class AccountBalances {

    private static final String SQL = """
            select a.id,
                   coalesce(sum(case
                       when t.account_id = a.id then
                           case when t.type in ('INCOME', 'REFUND') then t.amount else -t.amount end
                       when t.counterpart_account_id = a.id then t.amount
                   end), 0) as effect
              from accounts a
              left join transactions t
                     on t.deleted_at is null
                    and (t.account_id = a.id or t.counterpart_account_id = a.id)
             where a.user_id = ?
             group by a.id
            """;

    private final JdbcTemplate jdbc;

    AccountBalances(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Soma dos efeitos das transações por conta do usuário (some ao saldo de abertura para obter o saldo). */
    Map<UUID, BigDecimal> effectsByAccount(UUID userId) {
        Map<UUID, BigDecimal> result = new HashMap<>();
        jdbc.query(SQL, rs -> {
            result.put(rs.getObject("id", UUID.class), rs.getBigDecimal("effect"));
        }, userId);
        return result;
    }

    /** Se existe qualquer transação (inclusive excluída logicamente) tocando a conta. */
    boolean hasTransactions(UUID accountId) {
        Boolean found = jdbc.queryForObject(
                "select exists (select 1 from transactions where account_id = ? or counterpart_account_id = ?)",
                Boolean.class, accountId, accountId);
        return Boolean.TRUE.equals(found);
    }
}
