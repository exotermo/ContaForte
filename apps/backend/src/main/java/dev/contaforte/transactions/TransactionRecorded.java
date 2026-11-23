package dev.contaforte.transactions;

import java.util.UUID;

/**
 * Evento de domínio publicado quando uma transação é registrada. Traz só identificadores e o tipo: valor e
 * descrição são dados pessoais e nunca circulam em eventos, logs ou métricas.
 */
public record TransactionRecorded(UUID transactionId, UUID userId, UUID accountId, TransactionType type) {
}
