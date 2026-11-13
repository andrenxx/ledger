package dev.andrenatividade.ledger.transaction;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "ledger_transaction")
public class LedgerTransaction implements Persistable<UUID> {

    public enum Type { DEPOSIT, WITHDRAWAL, TRANSFER, REVERSAL }

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;

    private String description;

    @Column(name = "reverses_id")
    private UUID reversesId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Transient
    private boolean isNew = true; // id é gerado aqui, então o Spring Data precisa saber que é INSERT

    @OneToMany(mappedBy = "transaction", cascade = CascadeType.PERSIST)
    @OrderBy("id")
    private List<Posting> postings = new ArrayList<>();

    protected LedgerTransaction() {
    }

    LedgerTransaction(Type type, String description, UUID reversesId) {
        this.id = UUID.randomUUID();
        this.type = type;
        this.description = description;
        this.reversesId = reversesId;
        this.createdAt = Instant.now();
    }

    void add(Posting posting) {
        postings.add(posting);
    }

    @Override
    public UUID getId() {
        return id;
    }

    public Type getType() {
        return type;
    }

    public String getDescription() {
        return description;
    }

    public UUID getReversesId() {
        return reversesId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<Posting> getPostings() {
        return Collections.unmodifiableList(postings);
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
