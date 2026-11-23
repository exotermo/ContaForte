package dev.contaforte.summary;

import dev.contaforte.accounts.AccountType;
import dev.contaforte.accounts.Liquidity;
import dev.contaforte.common.DecimalString;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Posição financeira atual (RF-05) das contas ATIVAS do usuário. Contas arquivadas ficam de fora de todos os totais.
 * {@code availableCash} nunca inclui VA/VR (ADR-013). {@code creditCardBalance} é negativo quando há dívida.
 * {@code totalNetWorth} = soma de todas as parcelas.
 */
public record SummaryResponse(
        @DecimalString BigDecimal availableCash,
        @DecimalString BigDecimal restrictedBalance,
        @DecimalString BigDecimal investedAssets,
        @DecimalString BigDecimal creditCardBalance,
        @DecimalString BigDecimal otherBalance,
        @DecimalString BigDecimal totalNetWorth,
        List<AccountItem> accounts) {

    public record AccountItem(UUID id, String name, AccountType type, Liquidity liquidity,
                              @DecimalString BigDecimal balance) {}
}
