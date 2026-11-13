package dev.andrenatividade.ledger.transaction;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class TransactionDtos {

    private TransactionDtos() {
    }

    // amount em centavos (1050 = R$ 10,50)
    public record MoneyRequest(@Positive long amount, @Size(max = 200) String description) {
    }

    public record TransferRequest(
            @NotNull UUID fromAccountId,
            @NotNull UUID toAccountId,
            @Positive long amount,
            @Size(max = 200) String description) {
    }

    public record ReversalRequest(@Size(max = 200) String description) {
    }

    public record PostingResponse(UUID accountId, long amount, long balanceAfter) {
    }

    public record TransactionResponse(
            UUID id,
            LedgerTransaction.Type type,
            String description,
            UUID reversesId,
            String currency,
            Instant createdAt,
            List<PostingResponse> postings) {

        public static TransactionResponse of(LedgerTransaction tx) {
            var postings = tx.getPostings().stream()
                    .map(p -> new PostingResponse(p.getAccountId(), p.getAmount(), p.getBalanceAfter()))
                    .toList();
            var currency = tx.getPostings().isEmpty() ? null : tx.getPostings().getFirst().getCurrency();
            return new TransactionResponse(tx.getId(), tx.getType(), tx.getDescription(), tx.getReversesId(),
                    currency, tx.getCreatedAt(), postings);
        }
    }

    public record StatementEntry(
            long postingId,
            UUID transactionId,
            LedgerTransaction.Type type,
            String description,
            long amount,
            long balanceAfter,
            Instant createdAt) {

        public static StatementEntry of(Posting p) {
            var tx = p.getTransaction();
            return new StatementEntry(p.getId(), tx.getId(), tx.getType(), tx.getDescription(),
                    p.getAmount(), p.getBalanceAfter(), p.getCreatedAt());
        }
    }

    public record StatementPage(List<StatementEntry> entries, Long nextCursor) {
    }
}
