package dev.andrenatividade.ledger.idempotency;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class IdempotencyCleanup {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanup.class);

    private final JdbcClient jdbc;
    private final Duration retention;

    IdempotencyCleanup(JdbcClient jdbc, @Value("${ledger.idempotency.retention:24h}") Duration retention) {
        this.jdbc = jdbc;
        this.retention = retention;
    }

    // chave velha não serve pra nada: o cliente não vai mais repetir uma requisição de ontem
    @Scheduled(cron = "${ledger.idempotency.cleanup-cron:0 0 * * * *}")
    void purgeExpired() {
        int removed = jdbc.sql("delete from idempotency_key where created_at < now() - make_interval(secs => ?)")
                .param(retention.toSeconds())
                .update();
        if (removed > 0) {
            log.info("removed {} expired idempotency keys", removed);
        }
    }
}
