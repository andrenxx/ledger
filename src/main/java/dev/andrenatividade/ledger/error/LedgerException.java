package dev.andrenatividade.ledger.error;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;

public class LedgerException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> details;

    private LedgerException(HttpStatus status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public static LedgerException notFound(String what, UUID id) {
        return new LedgerException(HttpStatus.NOT_FOUND, "not-found", what + " " + id + " não existe", Map.of("id", id));
    }

    public static LedgerException insufficientFunds(UUID accountId, long balance, long requested) {
        return new LedgerException(HttpStatus.UNPROCESSABLE_CONTENT, "insufficient-funds",
                "Saldo insuficiente na conta " + accountId,
                Map.of("accountId", accountId, "balance", balance, "requested", requested));
    }

    public static LedgerException currencyMismatch(String expected, String actual) {
        return new LedgerException(HttpStatus.UNPROCESSABLE_CONTENT, "currency-mismatch",
                "As contas têm moedas diferentes (" + expected + " e " + actual + ")",
                Map.of("expected", expected, "actual", actual));
    }

    public static LedgerException sameAccount() {
        return new LedgerException(HttpStatus.UNPROCESSABLE_CONTENT, "same-account",
                "Origem e destino são a mesma conta", Map.of());
    }

    public static LedgerException unsupportedCurrency(String currency) {
        return new LedgerException(HttpStatus.UNPROCESSABLE_CONTENT, "unsupported-currency",
                "Moeda " + currency + " não é aceita", Map.of("currency", currency));
    }

    public static LedgerException alreadyReversed(UUID transactionId) {
        return new LedgerException(HttpStatus.CONFLICT, "already-reversed",
                "O lançamento " + transactionId + " já foi estornado", Map.of("transactionId", transactionId));
    }

    public static LedgerException cannotReverseReversal(UUID transactionId) {
        return new LedgerException(HttpStatus.CONFLICT, "cannot-reverse-reversal",
                "Um estorno não pode ser estornado", Map.of("transactionId", transactionId));
    }

    public static LedgerException idempotencyKeyReused(String key) {
        return new LedgerException(HttpStatus.UNPROCESSABLE_CONTENT, "idempotency-key-reused",
                "A chave de idempotência já foi usada com outro conteúdo", Map.of("idempotencyKey", key));
    }

    public static LedgerException unbalanced(long sum) {
        return new LedgerException(HttpStatus.INTERNAL_SERVER_ERROR, "unbalanced",
                "As partidas não somam zero", Map.of("sum", sum));
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}
