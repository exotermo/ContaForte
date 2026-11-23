package dev.contaforte.summary;

import dev.contaforte.accounts.AccountBalances;
import dev.contaforte.accounts.Liquidity;
import dev.contaforte.categories.CategoryRepository;
import dev.contaforte.categories.ContextRepository;
import dev.contaforte.categories.TransactionCategory;
import dev.contaforte.categories.TransactionContext;
import dev.contaforte.common.ApiException;
import dev.contaforte.common.AppTime;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resumos financeiros (RF-05, base do dashboard do M5). Os saldos vêm da mesma consulta das contas e a agregação
 * mensal é feita no banco (uma consulta agrupada): nenhuma transação é carregada em memória.
 */
@Service
class SummaryService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
    private static final YearMonth MIN_MONTH = YearMonth.of(2000, 1);
    private static final YearMonth MAX_MONTH = YearMonth.of(2100, 12);

    private static final String MONTHLY_SQL = """
            select type, category_id, context_id, sum(amount) as total
              from transactions
             where user_id = ? and deleted_at is null
               and occurred_at >= ? and occurred_at < ?
               and type in ('INCOME', 'EXPENSE', 'REFUND', 'INVESTMENT')
             group by type, category_id, context_id
            """;

    private final AccountBalances balances;
    private final CategoryRepository categories;
    private final ContextRepository contexts;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    SummaryService(AccountBalances balances, CategoryRepository categories, ContextRepository contexts,
                   JdbcTemplate jdbc, Clock clock) {
        this.balances = balances;
        this.categories = categories;
        this.contexts = contexts;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- posição atual

    @Transactional(readOnly = true)
    SummaryResponse current(UUID userId) {
        Map<Liquidity, BigDecimal> totals = new EnumMap<>(Liquidity.class);
        for (Liquidity l : Liquidity.values()) totals.put(l, ZERO);
        List<SummaryResponse.AccountItem> items = new ArrayList<>();
        for (AccountBalances.Balance b : balances.forUser(userId, false)) {
            Liquidity liquidity = b.type().liquidity();
            totals.merge(liquidity, b.balance(), BigDecimal::add);
            items.add(new SummaryResponse.AccountItem(b.id(), b.name(), b.type(), liquidity, b.balance()));
        }
        BigDecimal total = totals.values().stream().reduce(ZERO, BigDecimal::add);
        return new SummaryResponse(totals.get(Liquidity.AVAILABLE), totals.get(Liquidity.RESTRICTED),
                totals.get(Liquidity.INVESTED), totals.get(Liquidity.LIABILITY), totals.get(Liquidity.OTHER),
                total, items);
    }

    // ---------------------------------------------------------------- mês

    @Transactional(readOnly = true)
    MonthlySummaryResponse monthly(UUID userId, YearMonth requested) {
        YearMonth month = requested != null ? requested : YearMonth.now(clock.withZone(AppTime.ZONE));
        if (month.isBefore(MIN_MONTH) || month.isAfter(MAX_MONTH)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "O mês deve estar entre 2000-01 e 2100-12.");
        }
        OffsetDateTime from = month.atDay(1).atStartOfDay(AppTime.ZONE).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
        OffsetDateTime to = month.plusMonths(1).atDay(1).atStartOfDay(AppTime.ZONE).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);

        BigDecimal income = ZERO, expense = ZERO, refunds = ZERO, invested = ZERO;
        Map<UUID, BigDecimal> spendByCategory = new HashMap<>();   // "sem categoria/contexto" é somado à parte (chave nula)
        Map<UUID, BigDecimal> spendByContext = new HashMap<>();
        BigDecimal uncategorized = ZERO, noContext = ZERO;

        List<Row> rows = jdbc.query(MONTHLY_SQL, (ResultSet rs, int n) -> new Row(
                rs.getString("type"), rs.getObject("category_id", UUID.class),
                rs.getObject("context_id", UUID.class), rs.getBigDecimal("total")), userId, from, to);
        for (Row r : rows) {
            switch (r.type()) {
                case "INCOME" -> income = income.add(r.total());
                case "INVESTMENT" -> invested = invested.add(r.total());
                case "EXPENSE", "REFUND" -> {
                    BigDecimal signed = r.type().equals("EXPENSE") ? r.total() : r.total().negate();
                    if (r.type().equals("EXPENSE")) expense = expense.add(r.total()); else refunds = refunds.add(r.total());
                    if (r.categoryId() == null) uncategorized = uncategorized.add(signed);
                    else spendByCategory.merge(r.categoryId(), signed, BigDecimal::add);
                    if (r.contextId() == null) noContext = noContext.add(signed);
                    else spendByContext.merge(r.contextId(), signed, BigDecimal::add);
                }
                default -> { }
            }
        }
        BigDecimal netExpense = expense.subtract(refunds);
        return new MonthlySummaryResponse(month.toString(), income, expense, refunds, netExpense, invested,
                income.subtract(netExpense), categorySpend(userId, spendByCategory, uncategorized),
                contextSpend(userId, spendByContext, noContext));
    }

    /** Soma as subcategorias na categoria de 1º nível e ordena do maior gasto para o menor. */
    private List<MonthlySummaryResponse.CategorySpend> categorySpend(UUID userId, Map<UUID, BigDecimal> spend,
                                                                     BigDecimal uncategorized) {
        Map<UUID, TransactionCategory> byId = new HashMap<>();
        for (TransactionCategory c : categories.findVisibleTo(userId)) byId.put(c.getId(), c);

        Map<UUID, BigDecimal> topTotal = new LinkedHashMap<>();
        Map<UUID, BigDecimal> topDirect = new HashMap<>();
        Map<UUID, List<MonthlySummaryResponse.ChildSpend>> topChildren = new HashMap<>();
        for (Map.Entry<UUID, BigDecimal> e : spend.entrySet()) {
            if (e.getValue().signum() == 0) continue;
            TransactionCategory c = byId.get(e.getKey());
            UUID top = c.getParentId() != null ? c.getParentId() : c.getId();
            topTotal.merge(top, e.getValue(), BigDecimal::add);
            if (c.getParentId() == null) {
                topDirect.merge(top, e.getValue(), BigDecimal::add);
            } else {
                topChildren.computeIfAbsent(top, k -> new ArrayList<>())
                        .add(new MonthlySummaryResponse.ChildSpend(c.getId(), c.getName(), e.getValue()));
            }
        }
        List<MonthlySummaryResponse.CategorySpend> result = new ArrayList<>();
        for (Map.Entry<UUID, BigDecimal> e : topTotal.entrySet()) {
            TransactionCategory top = byId.get(e.getKey());
            List<MonthlySummaryResponse.ChildSpend> children = topChildren.getOrDefault(e.getKey(), List.of()).stream()
                    .sorted(Comparator.comparing(MonthlySummaryResponse.ChildSpend::total).reversed()
                            .thenComparing(MonthlySummaryResponse.ChildSpend::name))
                    .toList();
            result.add(new MonthlySummaryResponse.CategorySpend(top.getId(), top.getName(), top.getCode(),
                    top.isDefault(), e.getValue(), topDirect.getOrDefault(e.getKey(), ZERO), children));
        }
        if (uncategorized.signum() != 0) {
            result.add(new MonthlySummaryResponse.CategorySpend(null, "Sem categoria", null, true, uncategorized,
                    uncategorized, List.of()));
        }
        result.sort(Comparator.comparing(MonthlySummaryResponse.CategorySpend::total).reversed()
                .thenComparing(MonthlySummaryResponse.CategorySpend::name));
        return result;
    }

    private List<MonthlySummaryResponse.ContextSpend> contextSpend(UUID userId, Map<UUID, BigDecimal> spend,
                                                                   BigDecimal noContext) {
        Map<UUID, TransactionContext> byId = new HashMap<>();
        for (TransactionContext c : contexts.findVisibleTo(userId)) byId.put(c.getId(), c);
        List<MonthlySummaryResponse.ContextSpend> result = new ArrayList<>();
        for (Map.Entry<UUID, BigDecimal> e : spend.entrySet()) {
            if (e.getValue().signum() != 0) {
                result.add(new MonthlySummaryResponse.ContextSpend(e.getKey(), byId.get(e.getKey()).getName(), e.getValue()));
            }
        }
        if (noContext.signum() != 0) {
            result.add(new MonthlySummaryResponse.ContextSpend(null, "Sem contexto", noContext));
        }
        result.sort(Comparator.comparing(MonthlySummaryResponse.ContextSpend::total).reversed()
                .thenComparing(MonthlySummaryResponse.ContextSpend::name));
        return result;
    }

    private record Row(String type, UUID categoryId, UUID contextId, BigDecimal total) {}
}
