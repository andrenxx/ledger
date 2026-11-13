package dev.andrenatividade.ledger.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.andrenatividade.ledger.account.Account;
import dev.andrenatividade.ledger.account.AccountRepository;
import dev.andrenatividade.ledger.error.LedgerException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// regras do LedgerService sem banco; o comportamento com Postgres de verdade fica no LedgerApiTests
class LedgerServiceTest {

    AccountRepository accounts = mock(AccountRepository.class);
    LedgerTransactionRepository transactions = mock(LedgerTransactionRepository.class);
    LedgerService ledger;

    @BeforeEach
    void setUp() {
        ledger = new LedgerService(accounts, transactions, new SimpleMeterRegistry());
        when(transactions.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void unbalancedLegsAreRejectedBeforeTouchingTheDatabase() {
        var legs = List.of(new LedgerService.Leg(UUID.randomUUID(), -100), new LedgerService.Leg(UUID.randomUUID(), 99));

        assertThatThrownBy(() -> ledger.post(LedgerTransaction.Type.TRANSFER, null, null, legs))
                .isInstanceOf(LedgerException.class)
                .hasMessageContaining("não somam zero");
        verify(accounts, never()).lockAllById(anyCollection());
    }

    @Test
    void transferToTheSameAccountIsRejected() {
        var id = UUID.randomUUID();

        assertThatThrownBy(() -> ledger.transfer(id, id, 100, null))
                .isInstanceOf(LedgerException.class)
                .extracting(e -> ((LedgerException) e).getCode())
                .isEqualTo("same-account");
    }

    @Test
    void transferWritesOneDebitAndOneCreditWithRunningBalances() {
        var alice = Account.customer("Alice", "BRL");
        var bob = Account.customer("Bob", "BRL");
        alice.apply(1_000);
        when(accounts.lockAllById(anyCollection())).thenReturn(List.of(alice, bob));

        var tx = ledger.transfer(alice.getId(), bob.getId(), 300, "teste");

        assertThat(tx.getPostings())
                .extracting(Posting::getAccountId, Posting::getAmount, Posting::getBalanceAfter)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(alice.getId(), -300L, 700L),
                        org.assertj.core.groups.Tuple.tuple(bob.getId(), 300L, 300L));
    }

    @Test
    void mixedCurrenciesAreRejected() {
        var brl = Account.customer("Alice", "BRL");
        var usd = Account.customer("Bob", "USD");
        brl.apply(1_000);
        when(accounts.lockAllById(anyCollection())).thenReturn(List.of(brl, usd));

        assertThatThrownBy(() -> ledger.transfer(brl.getId(), usd.getId(), 100, null))
                .extracting(e -> ((LedgerException) e).getCode())
                .isEqualTo("currency-mismatch");
    }
}
