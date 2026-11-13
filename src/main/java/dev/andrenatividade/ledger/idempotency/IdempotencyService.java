package dev.andrenatividade.ledger.idempotency;

import dev.andrenatividade.ledger.error.LedgerException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

// A chave entra na mesma transação da operação: uma segunda requisição com a mesma chave
// fica esperando no INSERT até a primeira commitar. Se a operação falhar, o rollback leva a
// chave junto e o cliente pode tentar de novo.
@Service
public class IdempotencyService {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public IdempotencyService(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    public ResponseEntity<?> run(String key, String scope, Object request, HttpStatus successStatus, Supplier<?> operation) {
        var hash = sha256(scope + "\n" + json.writeValueAsString(request));

        int inserted = jdbc.sql("insert into idempotency_key (key, request_hash) values (?, ?) on conflict do nothing")
                .params(key, hash)
                .update();

        if (inserted == 0) {
            var stored = jdbc.sql("select request_hash, status, response_body from idempotency_key where key = ?")
                    .param(key)
                    .query((rs, i) -> new Stored(rs.getString(1), rs.getInt(2), rs.getString(3)))
                    .single();
            if (!stored.requestHash().equals(hash)) {
                throw LedgerException.idempotencyKeyReused(key);
            }
            return ResponseEntity.status(stored.status())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotent-Replayed", "true")
                    .body(stored.body());
        }

        var body = operation.get();
        jdbc.sql("update idempotency_key set status = ?, response_body = ? where key = ?")
                .params(successStatus.value(), json.writeValueAsString(body), key)
                .update();
        return ResponseEntity.status(successStatus).body(body);
    }

    private record Stored(String requestHash, int status, String body) {
    }

    private static String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
