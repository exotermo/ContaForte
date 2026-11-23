package dev.contaforte.accounts;

/** Tipos de conta e carteira. A liquidez de cada tipo é a regra central do produto (ADR-013). */
public enum AccountType {
    CHECKING_ACCOUNT(Liquidity.AVAILABLE),
    SAVINGS_ACCOUNT(Liquidity.AVAILABLE),
    CASH(Liquidity.AVAILABLE),
    CREDIT_CARD(Liquidity.LIABILITY),
    VA(Liquidity.RESTRICTED),
    VR(Liquidity.RESTRICTED),
    INVESTMENT_ACCOUNT(Liquidity.INVESTED),
    OTHER(Liquidity.OTHER);

    private final Liquidity liquidity;

    AccountType(Liquidity liquidity) {
        this.liquidity = liquidity;
    }

    public Liquidity liquidity() {
        return liquidity;
    }
}
