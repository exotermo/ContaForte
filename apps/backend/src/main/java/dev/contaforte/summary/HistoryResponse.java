package dev.contaforte.summary;

import dev.contaforte.common.DecimalString;
import java.math.BigDecimal;
import java.util.List;

/**
 * Série mensal do dashboard (RF-11), do mês mais antigo para o mais recente, com meses sem movimento zerados.
 * Mesmas regras de {@link MonthlySummaryResponse}: transferências não contam; {@code net} = receitas - gasto líquido.
 */
public record HistoryResponse(String from, String to, List<Month> months) {

    public record Month(String month,
                        @DecimalString BigDecimal income,
                        @DecimalString BigDecimal expense,
                        @DecimalString BigDecimal refunds,
                        @DecimalString BigDecimal netExpense,
                        @DecimalString BigDecimal invested,
                        @DecimalString BigDecimal net) {}
}
