package dev.contaforte.accounts;

/** Classe de liquidez de uma conta: define em qual totalizador o saldo entra (ADR-013). */
public enum Liquidity {
    /** Dinheiro que pode ser gasto ou investido agora (available_cash). */
    AVAILABLE,
    /** Saldo que só vale em usos específicos, como VA/VR (restricted_balance). Nunca é caixa disponível. */
    RESTRICTED,
    /** Patrimônio investido (invested_assets). */
    INVESTED,
    /** Dívida: saldo negativo que reduz apenas o patrimônio líquido (cartão de crédito). */
    LIABILITY,
    /** Entra só no patrimônio líquido. */
    OTHER
}
