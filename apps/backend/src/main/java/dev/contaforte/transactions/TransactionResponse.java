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
        UUID importId,
        ClassificationStatus classificationStatus,
        String classificationSource,
        BigDecimal classificationConfidence,
        UUID suggestedCategoryId,
        UUID suggestedContextId,
        Instant createdAt,
        Instant updatedAt) {

    public static TransactionResponse of(Transaction t) {
        return new TransactionResponse(t.getId(), t.getAccountId(), t.getCounterpartAccountId(), t.getType(),
                t.getAmount(), t.getCurrency(), t.getDescription(), t.getMerchant(), t.getCategoryId(),
                t.getContextId(), t.getOccurredAt(), t.getSource(), t.getImportId(),
                t.getClassificationStatus(), t.getClassificationSource(), t.getClassificationConfidence(),
                t.getSuggestedCategoryId(), t.getSuggestedContextId(), t.getCreatedAt(), t.getUpdatedAt());
    }
}
