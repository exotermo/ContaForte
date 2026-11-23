package dev.contaforte.imports.parse;

import dev.contaforte.transactions.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Uma linha do extrato já interpretada. Ou é válida (data, tipo e valor positivo preenchidos, {@code error} nulo) ou é
 * inválida (só {@code lineNo} e {@code error}). {@code lineNo} é a posição no arquivo: a linha do CSV ou a ordem da
 * transação no OFX.
 */
public record ParsedRow(int lineNo, Instant occurredAt, TransactionType type, BigDecimal amount, String description,
                        String merchant, String externalId, String error) {

    public static ParsedRow ok(int lineNo, Instant occurredAt, TransactionType type, BigDecimal amount,
                               String description, String merchant, String externalId) {
        return new ParsedRow(lineNo, occurredAt, type, amount, description, merchant, externalId, null);
    }

    public static ParsedRow invalid(int lineNo, String reason) {
        return new ParsedRow(lineNo, null, null, null, null, null, null, reason);
    }

    public boolean valid() {
        return error == null;
    }
}
