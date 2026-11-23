package dev.contaforte.transactions;

/**
 * Tipos de transação e o efeito de cada um no saldo (ADR-013). O valor é sempre positivo; o tipo dá o sinal.
 * TRANSFER e INVESTMENT movem dinheiro da conta de origem para a contraparte.
 */
public enum TransactionType {
    INCOME(+1, false),
    REFUND(+1, false),
    EXPENSE(-1, false),
    TRANSFER(-1, true),
    INVESTMENT(-1, true);

    private final int accountSign;
    private final boolean hasCounterpart;

    TransactionType(int accountSign, boolean hasCounterpart) {
        this.accountSign = accountSign;
        this.hasCounterpart = hasCounterpart;
    }

    /** +1 soma e -1 subtrai do saldo da conta de origem. */
    public int accountSign() {
        return accountSign;
    }

    /** Se exige uma conta de destino (e então soma o valor nela). */
    public boolean hasCounterpart() {
        return hasCounterpart;
    }
}
