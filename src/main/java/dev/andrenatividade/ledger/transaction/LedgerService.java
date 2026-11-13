package dev.andrenatividade.ledger.transaction;

import dev.andrenatividade.ledger.account.Account;
import dev.andrenatividade.ledger.account.AccountRepository;
import dev.andrenatividade.ledger.error.LedgerException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerService {

    public record Leg(UUID accountId, long amount) {
    }

    private final AccountRepository accounts;
    private final LedgerTransactionRepository transactions;
    private final MeterRegistry metrics;

    public LedgerService(AccountRepository accounts, LedgerTransactionRepository transactions, MeterRegistry metrics) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.metrics = metrics;
    }

    @Transactional
    public LedgerTransaction deposit(UUID accountId, long amount, String description) {
        var account = accounts.findById(accountId).orElseThrow(() -> LedgerException.notFound("Conta", accountId));
        var cash = systemAccount(account.getCurrency());
        return post(LedgerTransaction.Type.DEPOSIT, description, null,
                List.of(new Leg(account.getId(), amount), new Leg(cash.getId(), -amount)));
    }

    @Transactional
    public LedgerTransaction withdraw(UUID accountId, long amount, String description) {
        var account = accounts.findById(accountId).orElseThrow(() -> LedgerException.notFound("Conta", accountId));
        var cash = systemAccount(account.getCurrency());
        return post(LedgerTransaction.Type.WITHDRAWAL, description, null,
                List.of(new Leg(account.getId(), -amount), new Leg(cash.getId(), amount)));
    }

    @Transactional
    public LedgerTransaction transfer(UUID fromId, UUID toId, long amount, String description) {
        if (fromId.equals(toId)) {
            throw LedgerException.sameAccount();
        }
        return post(LedgerTransaction.Type.TRANSFER, description, null,
                List.of(new Leg(fromId, -amount), new Leg(toId, amount)));
    }

    @Transactional
    public LedgerTransaction reverse(UUID transactionId, String description) {
        var original = transactions.findWithPostings(transactionId)
                .orElseThrow(() -> LedgerException.notFound("Lançamento", transactionId));
        if (original.getType() == LedgerTransaction.Type.REVERSAL) {
            throw LedgerException.cannotReverseReversal(transactionId);
        }
        if (transactions.existsByReversesId(transactionId)) {
            throw LedgerException.alreadyReversed(transactionId);
        }
        var legs = original.getPostings().stream()
                .map(p -> new Leg(p.getAccountId(), -p.getAmount()))
                .toList();
        var text = description != null ? description : "Estorno de " + transactionId;
        return post(LedgerTransaction.Type.REVERSAL, text, transactionId, legs);
    }

    LedgerTransaction post(LedgerTransaction.Type type, String description, UUID reversesId, List<Leg> legs) {
        if (legs.size() < 2) {
            throw new IllegalArgumentException("Um lançamento precisa de pelo menos duas partidas");
        }
        long sum = legs.stream().mapToLong(Leg::amount).sum();
        if (sum != 0) {
            throw LedgerException.unbalanced(sum);
        }

        var ids = legs.stream().map(Leg::accountId).distinct().toList();
        Map<UUID, Account> locked = accounts.lockAllById(ids).stream()
                .collect(Collectors.toMap(Account::getId, Function.identity()));
        for (var id : ids) {
            if (!locked.containsKey(id)) {
                throw LedgerException.notFound("Conta", id);
            }
        }

        var currency = locked.get(legs.getFirst().accountId()).getCurrency();
        for (var account : locked.values()) {
            if (!account.getCurrency().equals(currency)) {
                throw LedgerException.currencyMismatch(currency, account.getCurrency());
            }
        }

        var tx = new LedgerTransaction(type, description, reversesId);
        // débitos primeiro, pra falhar antes de creditar alguém
        legs.stream()
                .sorted((a, b) -> Long.compare(a.amount(), b.amount()))
                .forEach(leg -> {
                    long balanceAfter = locked.get(leg.accountId()).apply(leg.amount());
                    tx.add(new Posting(tx, leg.accountId(), leg.amount(), currency, balanceAfter));
                });

        var saved = transactions.save(tx);
        metrics.counter("ledger.transactions", "type", type.name()).increment();
        metrics.summary("ledger.transaction.amount", "type", type.name(), "currency", currency)
                .record(legs.stream().filter(l -> l.amount() > 0).mapToLong(Leg::amount).sum());
        return saved;
    }

    private Account systemAccount(String currency) {
        return accounts.findSystemAccount(currency).orElseThrow(() -> LedgerException.unsupportedCurrency(currency));
    }
}
