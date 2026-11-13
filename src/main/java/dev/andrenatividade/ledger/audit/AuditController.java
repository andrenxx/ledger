package dev.andrenatividade.ledger.audit;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// TODO: proteger com auth de admin
@RestController
@RequestMapping("/audit")
public class AuditController {

    public record Mismatch(UUID accountId, long storedBalance, long postingsSum) {
    }

    public record TrialBalance(long postings, long sumOfAllPostings, boolean balanced, List<Mismatch> mismatchedAccounts) {
    }

    private final JdbcClient jdbc;

    public AuditController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // REPEATABLE READ pra contar e somar sobre o mesmo snapshot
    @GetMapping("/trial-balance")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TrialBalance trialBalance() {
        var totals = jdbc.sql("select count(*), coalesce(sum(amount), 0) from posting")
                .query((rs, i) -> new long[] {rs.getLong(1), rs.getLong(2)})
                .single();
        var mismatches = jdbc.sql("""
                        select a.id, a.balance, coalesce(sum(p.amount), 0) as total
                        from account a left join posting p on p.account_id = a.id
                        group by a.id, a.balance
                        having a.balance <> coalesce(sum(p.amount), 0)
                        """)
                .query((rs, i) -> new Mismatch(rs.getObject(1, UUID.class), rs.getLong(2), rs.getLong(3)))
                .list();
        return new TrialBalance(totals[0], totals[1], totals[1] == 0 && mismatches.isEmpty(), mismatches);
    }
}
