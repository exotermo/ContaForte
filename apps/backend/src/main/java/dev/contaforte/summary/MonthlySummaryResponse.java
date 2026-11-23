package dev.contaforte.summary;

import dev.contaforte.common.DecimalString;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Movimentação de um mês (fuso America/Sao_Paulo). Transferências e investimentos NÃO são receita nem gasto (pagar a
 * fatura do cartão não duplica a despesa); o investido do mês vem em {@code invested}.
 * {@code netExpense} = gastos − estornos; {@code net} = receitas − gasto líquido.
 * Os totais por categoria e contexto usam o gasto líquido.
 */
public record MonthlySummaryResponse(
        String month,
        @DecimalString BigDecimal income,
        @DecimalString BigDecimal expense,
        @DecimalString BigDecimal refunds,
        @DecimalString BigDecimal netExpense,
        @DecimalString BigDecimal invested,
        @DecimalString BigDecimal net,
        List<CategorySpend> byCategory,
        List<ContextSpend> byContext) {

    /**
     * Gasto de uma categoria de 1º nível, já somadas as subcategorias. {@code directTotal} é o que foi lançado direto
     * nela (sem subcategoria). A linha "Sem categoria" tem {@code categoryId} nulo.
     */
    public record CategorySpend(UUID categoryId, String name, String code, boolean system,
                                @DecimalString BigDecimal total, @DecimalString BigDecimal directTotal,
                                List<ChildSpend> children) {}

    public record ChildSpend(UUID categoryId, String name, @DecimalString BigDecimal total) {}

    /** A linha "Sem contexto" tem {@code contextId} nulo. */
    public record ContextSpend(UUID contextId, String name, @DecimalString BigDecimal total) {}
}
