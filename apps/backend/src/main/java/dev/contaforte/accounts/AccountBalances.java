package dev.contaforte.accounts;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Único lugar que calcula saldos (o saldo nunca é armazenado, ADR-013): uma consulta agregada no banco soma o efeito
 * das transações por conta. O sinal por tipo espelha {@code TransactionType.accountSign()}; um teste confere os dois.
 * Contas e resumo usam esta mesma consulta.
 */
@Component
public class AccountBalances {

    /** Saldo atual de uma conta (abertura + efeito das transações). */
    public record Balance(UUID id, String name, AccountType type, BigDecimal balance, boolean archived) {}


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
    private final AccountRepository accounts;

    AccountBalances(JdbcTemplate jdbc, AccountRepository accounts) {
        this.jdbc = jdbc;
        this.accounts = accounts;
    }

    /** Saldos das contas do usuário, em ordem de nome; arquivadas só se pedido. */
    public List<Balance> forUser(UUID userId, boolean includeArchived) {
        Map<UUID, BigDecimal> effects = effectsByAccount(userId);
        List<Balance> result = new ArrayList<>();
        for (Account a : accounts.findByUserIdOrderByNameAsc(userId)) {
            if (includeArchived || !a.isArchived()) {
                result.add(new Balance(a.getId(), a.getName(), a.getType(), balanceOf(a, effects), a.isArchived()));
            }
        }
        return result;
    }

    static BigDecimal balanceOf(Account a, Map<UUID, BigDecimal> effects) {
        return a.getOpeningBalance().add(effects.getOrDefault(a.getId(), BigDecimal.ZERO)).setScale(2);
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
