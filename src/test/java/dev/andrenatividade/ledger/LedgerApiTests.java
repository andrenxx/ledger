package dev.andrenatividade.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;

/** Testes de ponta a ponta: API HTTP real + PostgreSQL real (Testcontainers). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LedgerApiTests {

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    RestClient http;

    @BeforeEach
    void setUp() {
        http = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> { })
                .build();
    }

    // ---------- helpers ----------

    @SuppressWarnings("unchecked")
    ResponseEntity<Map<String, Object>> post(String path, Object body, String key) {
        var req = http.post().uri(path).contentType(MediaType.APPLICATION_JSON);
        if (key != null) {
            req = req.header("Idempotency-Key", key);
        }
        return (ResponseEntity<Map<String, Object>>) (ResponseEntity<?>) req.body(body).retrieve().toEntity(Map.class);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> get(String path) {
        return http.get().uri(path).retrieve().body(Map.class);
    }

    String newAccount(String name) {
        var res = post("/accounts", Map.of("ownerName", name, "currency", "BRL"), null);
        assertThat(res.getStatusCode().value()).isEqualTo(201);
        return (String) res.getBody().get("id");
    }

    long balance(String accountId) {
        return ((Number) get("/accounts/" + accountId).get("balance")).longValue();
    }

    String key() {
        return UUID.randomUUID().toString();
    }

    void deposit(String accountId, long amount) {
        var res = post("/accounts/" + accountId + "/deposits", Map.of("amount", amount), key());
        assertThat(res.getStatusCode().value()).isEqualTo(201);
    }

    Map<String, Object> transfer(String from, String to, long amount) {
        return Map.of("fromAccountId", from, "toAccountId", to, "amount", amount);
    }

    void assertLedgerBalanced() {
        var tb = get("/audit/trial-balance");
        assertThat(tb.get("balanced")).as("trial balance %s", tb).isEqualTo(true);
        assertThat(((Number) tb.get("sumOfAllPostings")).longValue()).isZero();
    }

    // ---------- tests ----------

    @Test
    void depositAndTransferMoveMoneyWithDoubleEntry() {
        var alice = newAccount("Alice");
        var bob = newAccount("Bob");
        deposit(alice, 10_000);

        var res = post("/transfers", transfer(alice, bob, 2_550), key());

        assertThat(res.getStatusCode().value()).isEqualTo(201);
        @SuppressWarnings("unchecked")
        var postings = (List<Map<String, Object>>) res.getBody().get("postings");
        assertThat(postings).hasSize(2);
        assertThat(postings.stream().mapToLong(p -> ((Number) p.get("amount")).longValue()).sum()).isZero();
        assertThat(balance(alice)).isEqualTo(7_450);
        assertThat(balance(bob)).isEqualTo(2_550);
        assertLedgerBalanced();
    }

    @Test
    void transferWithoutFundsIsRejectedAndChangesNothing() {
        var alice = newAccount("Alice");
        var bob = newAccount("Bob");
        deposit(alice, 1_000);

        var res = post("/transfers", transfer(alice, bob, 1_001), key());

        assertThat(res.getStatusCode().value()).isEqualTo(422);
        assertThat(res.getBody().get("title")).isEqualTo("insufficient-funds");
        assertThat(balance(alice)).isEqualTo(1_000);
        assertThat(balance(bob)).isZero();
        assertLedgerBalanced();
    }

    @Test
    void sameIdempotencyKeyRunsOnceAndReplaysTheResponse() {
        var alice = newAccount("Alice");
        var bob = newAccount("Bob");
        deposit(alice, 5_000);
        var k = key();

        var first = post("/transfers", transfer(alice, bob, 1_000), k);
        var second = post("/transfers", transfer(alice, bob, 1_000), k);

        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(second.getStatusCode().value()).isEqualTo(201);
        assertThat(second.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(second.getBody().get("id")).isEqualTo(first.getBody().get("id"));
        assertThat(balance(alice)).isEqualTo(4_000);
        assertThat(balance(bob)).isEqualTo(1_000);
    }

    @Test
    void reusingAKeyWithADifferentBodyIsRejected() {
        var alice = newAccount("Alice");
        var bob = newAccount("Bob");
        deposit(alice, 5_000);
        var k = key();

        post("/transfers", transfer(alice, bob, 1_000), k);
        var other = post("/transfers", transfer(alice, bob, 2_000), k);

        assertThat(other.getStatusCode().value()).isEqualTo(422);
        assertThat(other.getBody().get("title")).isEqualTo("idempotency-key-reused");
        assertThat(balance(alice)).isEqualTo(4_000);
    }

    @Test
    void missingIdempotencyKeyIsABadRequest() {
        var alice = newAccount("Alice");
        var res = post("/accounts/" + alice + "/deposits", Map.of("amount", 100), null);
        assertThat(res.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void concurrentTransfersNeverOverdraw() throws Exception {
        var alice = newAccount("Alice");
        var bob = newAccount("Bob");
        deposit(alice, 1_000);

        // 50 transferências de 30 ao mesmo tempo = 1.500, mas só há 1.000: exatamente 33 passam.
        var ok = new AtomicInteger();
        var rejected = new AtomicInteger();
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            var futures = IntStream.range(0, 50)
                    .mapToObj(i -> pool.submit(() -> {
                        var status = post("/transfers", transfer(alice, bob, 30), key()).getStatusCode().value();
                        if (status == 201) ok.incrementAndGet();
                        else if (status == 422) rejected.incrementAndGet();
                    }))
                    .toList();
            for (var f : futures) f.get();
        }

        assertThat(ok.get()).isEqualTo(33);
        assertThat(rejected.get()).isEqualTo(17);
        assertThat(balance(alice)).isEqualTo(10);
        assertThat(balance(bob)).isEqualTo(990);
        assertLedgerBalanced();
    }

    @Test
    void crossingTransfersDoNotDeadlock() throws Exception {
        var alice = newAccount("Alice");
        var bob = newAccount("Bob");
        deposit(alice, 100_000);
        deposit(bob, 100_000);

        // A→B e B→A ao mesmo tempo: sem ordem de trava, isso dá deadlock.
        var failures = new AtomicInteger();
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            var futures = IntStream.range(0, 100)
                    .mapToObj(i -> pool.submit(() -> {
                        var body = i % 2 == 0 ? transfer(alice, bob, 100) : transfer(bob, alice, 100);
                        if (post("/transfers", body, key()).getStatusCode().value() != 201) {
                            failures.incrementAndGet();
                        }
                    }))
                    .toList();
            for (var f : futures) f.get();
        }

        assertThat(failures.get()).isZero();
        assertThat(balance(alice)).isEqualTo(100_000);
        assertThat(balance(bob)).isEqualTo(100_000);
        assertLedgerBalanced();
    }

    @Test
    void reversalRestoresBalancesAndCanOnlyHappenOnce() {
        var alice = newAccount("Alice");
        var bob = newAccount("Bob");
        deposit(alice, 3_000);
        var tx = (String) post("/transfers", transfer(alice, bob, 1_200), key()).getBody().get("id");

        var reversal = post("/transactions/" + tx + "/reversal", Map.of(), key());
        var again = post("/transactions/" + tx + "/reversal", Map.of(), key());

        assertThat(reversal.getStatusCode().value()).isEqualTo(201);
        assertThat(reversal.getBody().get("reversesId")).isEqualTo(tx);
        assertThat(again.getStatusCode().value()).isEqualTo(409);
        assertThat(balance(alice)).isEqualTo(3_000);
        assertThat(balance(bob)).isZero();
        assertLedgerBalanced();
    }

    @Test
    void statementIsPaginatedNewestFirst() {
        var alice = newAccount("Alice");
        for (int i = 1; i <= 5; i++) {
            deposit(alice, i * 100);
        }

        var page1 = get("/accounts/" + alice + "/entries?limit=2");
        @SuppressWarnings("unchecked")
        var entries1 = (List<Map<String, Object>>) page1.get("entries");
        assertThat(entries1).extracting(e -> ((Number) e.get("amount")).longValue()).containsExactly(500L, 400L);

        var page2 = get("/accounts/" + alice + "/entries?limit=2&before=" + page1.get("nextCursor"));
        @SuppressWarnings("unchecked")
        var entries2 = (List<Map<String, Object>>) page2.get("entries");
        assertThat(entries2).extracting(e -> ((Number) e.get("amount")).longValue()).containsExactly(300L, 200L);
    }

    @Test
    void ledgerRowsCannotBeEditedOrDeleted() {
        var alice = newAccount("Alice");
        deposit(alice, 100);

        assertThatThrownBy(() -> jdbc.sql("update posting set amount = 999999").update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("delete from posting").update())
                .hasMessageContaining("append-only");
    }

    @Test
    void transfersBetweenDifferentCurrenciesAreRejected() {
        var alice = newAccount("Alice");
        var usd = (String) post("/accounts", Map.of("ownerName", "Bob", "currency", "USD"), null).getBody().get("id");
        deposit(alice, 1_000);

        var res = post("/transfers", transfer(alice, usd, 100), key());

        assertThat(res.getStatusCode().value()).isEqualTo(422);
        assertThat(res.getBody().get("title")).isEqualTo("currency-mismatch");
    }
}
