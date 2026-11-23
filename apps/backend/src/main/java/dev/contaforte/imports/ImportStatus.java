package dev.contaforte.imports;

/**
 * Ciclo de vida de uma importação (ADR-015): a prévia ainda não criou nenhuma transação; a confirmada criou; a desfeita
 * teve suas transações excluídas logicamente. Prévia descartada ou vencida simplesmente deixa de existir.
 */
public enum ImportStatus {
    PREVIEW, CONFIRMED, ROLLED_BACK
}
