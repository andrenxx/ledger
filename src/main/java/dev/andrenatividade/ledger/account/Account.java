package dev.andrenatividade.ledger.account;

import dev.andrenatividade.ledger.error.LedgerException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "account")
public class Account implements Persistable<UUID> {

    public enum Kind { CUSTOMER, SYSTEM }

    @Id
    private UUID id;

    @Column(name = "owner_name", nullable = false)
    private String ownerName;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;

    @Column(nullable = false)
    private long balance; // centavos

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Transient
    private boolean isNew = true; // id é gerado aqui, então o Spring Data precisa saber que é INSERT

    protected Account() {
    }

    public static Account customer(String ownerName, String currency) {
        var account = new Account();
        account.id = UUID.randomUUID();
        account.ownerName = ownerName;
        account.currency = currency;
        account.kind = Kind.CUSTOMER;
        account.balance = 0;
        account.createdAt = Instant.now();
        return account;
    }

    // conta SYSTEM pode ficar negativa: é o "lado de fora" do ledger (dinheiro que entrou/saiu)
    public long apply(long amount) {
        long next = Math.addExact(balance, amount);
        if (kind == Kind.CUSTOMER && next < 0) {
            throw LedgerException.insufficientFunds(id, balance, -amount);
        }
        balance = next;
        return next;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public String getCurrency() {
        return currency;
    }

    public Kind getKind() {
        return kind;
    }

    public long getBalance() {
        return balance;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        isNew = false;
    }
}
