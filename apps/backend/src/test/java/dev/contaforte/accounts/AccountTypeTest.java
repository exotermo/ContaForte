package dev.contaforte.accounts;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AccountTypeTest {

    @Test
    void liquidezDeCadaTipoSegueOAdr013() {
        assertThat(AccountType.CHECKING_ACCOUNT.liquidity()).isEqualTo(Liquidity.AVAILABLE);
        assertThat(AccountType.SAVINGS_ACCOUNT.liquidity()).isEqualTo(Liquidity.AVAILABLE);
        assertThat(AccountType.CASH.liquidity()).isEqualTo(Liquidity.AVAILABLE);
        assertThat(AccountType.VA.liquidity()).isEqualTo(Liquidity.RESTRICTED);
        assertThat(AccountType.VR.liquidity()).isEqualTo(Liquidity.RESTRICTED);
        assertThat(AccountType.INVESTMENT_ACCOUNT.liquidity()).isEqualTo(Liquidity.INVESTED);
        assertThat(AccountType.CREDIT_CARD.liquidity()).isEqualTo(Liquidity.LIABILITY);
        assertThat(AccountType.OTHER.liquidity()).isEqualTo(Liquidity.OTHER);
    }

    @Test
    void vaEVrNuncaSaoCaixaDisponivel() {
        // promessa central do produto: benefício alimentação/refeição não é capital para investir
        assertThat(AccountType.VA.liquidity()).isNotEqualTo(Liquidity.AVAILABLE);
        assertThat(AccountType.VR.liquidity()).isNotEqualTo(Liquidity.AVAILABLE);
    }

    @Test
    void todosOsOitoTiposDoSddExistem() {
        assertThat(AccountType.values()).extracting(Enum::name).containsExactlyInAnyOrder(
                "CHECKING_ACCOUNT", "SAVINGS_ACCOUNT", "CASH", "CREDIT_CARD",
                "VA", "VR", "INVESTMENT_ACCOUNT", "OTHER");
    }
}
