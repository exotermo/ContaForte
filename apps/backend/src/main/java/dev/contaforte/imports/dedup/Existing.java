package dev.contaforte.imports.dedup;

import dev.contaforte.common.AppTime;
import dev.contaforte.imports.parse.TextNorm;
import dev.contaforte.transactions.Transaction;
import dev.contaforte.transactions.TransactionType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Uma transação já existente na conta, vista pelo seu EFEITO nela: receita ou despesa. Uma transferência que sai da
 * conta pesa como despesa, e uma que chega, como receita (é por isso que um extrato "duplica" uma transferência lançada
 * à mão). O identificador externo só vale do lado em que ele foi importado.
 */
public record Existing(UUID id, TransactionType effect, long cents, Instant occurredAt, LocalDate day, String externalId,
                       String text, String pixId) {

    public static Existing of(Transaction t, UUID accountId) {
        boolean origin = t.getAccountId().equals(accountId);
        TransactionType effect = !origin || t.getType() == TransactionType.INCOME || t.getType() == TransactionType.REFUND
                ? TransactionType.INCOME : TransactionType.EXPENSE;
        String source = t.getDescription() != null ? t.getDescription() : t.getMerchant();
        String both = (t.getDescription() == null ? "" : t.getDescription()) + " " + (t.getMerchant() == null ? "" : t.getMerchant());
        return new Existing(t.getId(), effect, t.getAmount().movePointRight(2).longValueExact(), t.getOccurredAt(),
                t.getOccurredAt().atZone(AppTime.ZONE).toLocalDate(), origin ? t.getExternalId() : null,
                TextNorm.key(source), PixId.extract(both));
    }
}
