package dev.andrenatividade.ledger.transaction;

import dev.andrenatividade.ledger.idempotency.IdempotencyService;
import dev.andrenatividade.ledger.transaction.TransactionDtos.MoneyRequest;
import dev.andrenatividade.ledger.transaction.TransactionDtos.ReversalRequest;
import dev.andrenatividade.ledger.transaction.TransactionDtos.TransactionResponse;
import dev.andrenatividade.ledger.transaction.TransactionDtos.TransferRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
public class TransactionController {

    private static final String KEY = "Idempotency-Key";

    private final LedgerService ledger;
    private final IdempotencyService idempotency;

    public TransactionController(LedgerService ledger, IdempotencyService idempotency) {
        this.ledger = ledger;
        this.idempotency = idempotency;
    }

    @PostMapping("/accounts/{id}/deposits")
    public ResponseEntity<?> deposit(@RequestHeader(KEY) @Size(min = 1, max = 100) String key,
                                     @PathVariable UUID id, @Valid @RequestBody MoneyRequest req) {
        return idempotency.run(key, "deposit:" + id, req, HttpStatus.CREATED,
                () -> TransactionResponse.of(ledger.deposit(id, req.amount(), req.description())));
    }

    @PostMapping("/accounts/{id}/withdrawals")
    public ResponseEntity<?> withdraw(@RequestHeader(KEY) @Size(min = 1, max = 100) String key,
                                      @PathVariable UUID id, @Valid @RequestBody MoneyRequest req) {
        return idempotency.run(key, "withdrawal:" + id, req, HttpStatus.CREATED,
                () -> TransactionResponse.of(ledger.withdraw(id, req.amount(), req.description())));
    }

    @PostMapping("/transfers")
    public ResponseEntity<?> transfer(@RequestHeader(KEY) @Size(min = 1, max = 100) String key,
                                      @Valid @RequestBody TransferRequest req) {
        return idempotency.run(key, "transfer", req, HttpStatus.CREATED,
                () -> TransactionResponse.of(ledger.transfer(req.fromAccountId(), req.toAccountId(),
                        req.amount(), req.description())));
    }

    @PostMapping("/transactions/{id}/reversal")
    public ResponseEntity<?> reverse(@RequestHeader(KEY) @Size(min = 1, max = 100) String key,
                                     @PathVariable UUID id,
                                     @Valid @RequestBody(required = false) ReversalRequest req) {
        var body = req != null ? req : new ReversalRequest(null);
        return idempotency.run(key, "reversal:" + id, body, HttpStatus.CREATED,
                () -> TransactionResponse.of(ledger.reverse(id, body.description())));
    }
}
