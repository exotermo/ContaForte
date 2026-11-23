package dev.contaforte.transactions;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Visão pública da transação. O valor sai como string decimal ("120.50"), nunca como número JSON. */
public record TransactionResponse(
        UUID id,
        UUID accountId,
        UUID counterpartAccountId,
        TransactionType type,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        String currency,
        String description,
        String merchant,
        UUID categoryId,
        UUID contextId,
        Instant occurredAt,
        TransactionSource source,
        Instant createdAt,
        Instant updatedAt) {

    static TransactionResponse of(Transaction t) {
        return new TransactionResponse(t.getId(), t.getAccountId(), t.getCounterpartAccountId(), t.getType(),
                t.getAmount(), t.getCurrency(), t.getDescription(), t.getMerchant(), t.getCategoryId(),
                t.getContextId(), t.getOccurredAt(), t.getSource(), t.getCreatedAt(), t.getUpdatedAt());
    }
}
