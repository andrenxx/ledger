package dev.andrenatividade.ledger.account;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    // ORDER BY id: todo mundo trava na mesma ordem, senão A->B e B->A simultâneos dão deadlock
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id in :ids order by a.id")
    List<Account> lockAllById(Collection<UUID> ids);

    @Query("select a from Account a where a.kind = dev.andrenatividade.ledger.account.Account.Kind.SYSTEM and a.currency = :currency")
    Optional<Account> findSystemAccount(String currency);
}
