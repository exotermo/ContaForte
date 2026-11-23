package dev.contaforte.transactions;

/** Origem do lançamento. No Milestone 2 só MANUAL é usada; as demais entram com importação e integrações. */
public enum TransactionSource {
    MANUAL, PIX, OPEN_FINANCE, OFX, CSV, SYSTEM
}
