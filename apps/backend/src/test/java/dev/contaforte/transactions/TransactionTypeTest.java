package dev.contaforte.transactions;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TransactionTypeTest {

    @Test
    void sinalNaContaDeOrigem() {
        assertThat(TransactionType.INCOME.accountSign()).isEqualTo(+1);
        assertThat(TransactionType.REFUND.accountSign()).isEqualTo(+1);
        assertThat(TransactionType.EXPENSE.accountSign()).isEqualTo(-1);
        assertThat(TransactionType.TRANSFER.accountSign()).isEqualTo(-1);
        assertThat(TransactionType.INVESTMENT.accountSign()).isEqualTo(-1);
    }

    @Test
    void soTransferenciaEInvestimentoTemContraparte() {
        assertThat(TransactionType.values()).filteredOn(TransactionType::hasCounterpart)
                .containsExactlyInAnyOrder(TransactionType.TRANSFER, TransactionType.INVESTMENT);
    }
}
