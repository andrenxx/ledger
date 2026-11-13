package dev.andrenatividade.ledger.transaction;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, UUID> {

    @Query("select t from LedgerTransaction t left join fetch t.postings where t.id = :id")
    Optional<LedgerTransaction> findWithPostings(UUID id);

    boolean existsByReversesId(UUID reversesId);

    // keyset em vez de offset: a página não "anda" quando chegam lançamentos novos
    @Query("""
            select p from Posting p join fetch p.transaction
            where p.accountId = :accountId and (:before is null or p.id < :before)
            order by p.id desc
            """)
    List<Posting> findStatement(UUID accountId, Long before, Limit limit);
}
