package dev.andrenatividade.ledger.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.andrenatividade.ledger.error.LedgerException;
import org.junit.jupiter.api.Test;

class AccountTest {

    @Test
    void creditAndDebitMoveTheBalance() {
        var account = Account.customer("Alice", "BRL");

        assertThat(account.apply(1_000)).isEqualTo(1_000);
        assertThat(account.apply(-400)).isEqualTo(600);
    }

    @Test
    void customerCannotGoNegative() {
        var account = Account.customer("Alice", "BRL");
        account.apply(100);

        assertThatThrownBy(() -> account.apply(-101))
                .isInstanceOf(LedgerException.class)
                .extracting(e -> ((LedgerException) e).getCode())
                .isEqualTo("insufficient-funds");
        assertThat(account.getBalance()).isEqualTo(100);
    }

    @Test
    void overflowIsAnErrorNotAWrapAround() {
        var account = Account.customer("Alice", "BRL");
        account.apply(Long.MAX_VALUE);

        assertThatThrownBy(() -> account.apply(1)).isInstanceOf(ArithmeticException.class);
    }
}
