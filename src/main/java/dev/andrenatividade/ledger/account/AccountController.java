package dev.andrenatividade.ledger.account;

import dev.andrenatividade.ledger.error.LedgerException;
import dev.andrenatividade.ledger.transaction.LedgerTransactionRepository;
import dev.andrenatividade.ledger.transaction.TransactionDtos.StatementPage;
import dev.andrenatividade.ledger.transaction.TransactionDtos;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/accounts")
@Validated
public class AccountController {

    public record CreateAccountRequest(
            @NotBlank @Size(max = 120) String ownerName,
            @NotBlank @Pattern(regexp = "BRL|USD|EUR", message = "deve ser BRL, USD ou EUR") String currency) {
    }

    public record AccountResponse(UUID id, String ownerName, String currency, String kind, long balance, Instant createdAt) {
        static AccountResponse of(Account a) {
            return new AccountResponse(a.getId(), a.getOwnerName(), a.getCurrency(), a.getKind().name(),
                    a.getBalance(), a.getCreatedAt());
        }
    }

    private final AccountRepository accounts;
    private final LedgerTransactionRepository transactions;

    public AccountController(AccountRepository accounts, LedgerTransactionRepository transactions) {
        this.accounts = accounts;
        this.transactions = transactions;
    }

    @PostMapping
    @Transactional
    public ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest req) {
        var account = accounts.save(Account.customer(req.ownerName().strip(), req.currency()));
        return ResponseEntity.created(URI.create("/accounts/" + account.getId())).body(AccountResponse.of(account));
    }

    @GetMapping("/{id}")
    public AccountResponse get(@PathVariable UUID id) {
        return accounts.findById(id).map(AccountResponse::of)
                .orElseThrow(() -> LedgerException.notFound("Conta", id));
    }

    @GetMapping("/{id}/entries")
    @Transactional(readOnly = true)
    public StatementPage statement(@PathVariable UUID id,
                                   @RequestParam(required = false) Long before,
                                   @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        if (!accounts.existsById(id)) {
            throw LedgerException.notFound("Conta", id);
        }
        var page = transactions.findStatement(id, before, Limit.of(limit + 1));
        var hasMore = page.size() > limit;
        var entries = page.stream().limit(limit).map(TransactionDtos.StatementEntry::of).toList();
        Long next = hasMore ? entries.getLast().postingId() : null;
        return new StatementPage(entries, next);
    }
}
