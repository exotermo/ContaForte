package dev.contaforte.accounts;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Visão pública da conta. Valores monetários saem como string decimal ("1234.50"), nunca como número JSON,
 * para o cliente não passar por ponto flutuante.
 */
public record AccountResponse(
        UUID id,
        String name,
        AccountType type,
        Liquidity liquidity,
        String currency,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal openingBalance,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal balance,
        boolean archived,
        Instant createdAt) {

    static AccountResponse of(Account a, BigDecimal balance) {
        return new AccountResponse(a.getId(), a.getName(), a.getType(), a.getType().liquidity(), a.getCurrency(),
                a.getOpeningBalance(), balance, a.isArchived(), a.getCreatedAt());
    }
}
