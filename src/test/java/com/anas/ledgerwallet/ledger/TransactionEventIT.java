package com.anas.ledgerwallet.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.anas.ledgerwallet.IntegrationTestBase;
import com.anas.ledgerwallet.account.dto.AccountResponse;
import com.anas.ledgerwallet.audit.AuditLogEntry;
import com.anas.ledgerwallet.audit.AuditLogRepository;
import com.anas.ledgerwallet.auth.dto.AuthResponse;
import com.anas.ledgerwallet.auth.dto.LoginRequest;
import com.anas.ledgerwallet.auth.dto.RegisterRequest;
import com.anas.ledgerwallet.auth.dto.UserResponse;
import com.anas.ledgerwallet.ledger.dto.MoneyMovementRequest;
import com.anas.ledgerwallet.ledger.dto.TransactionResponse;
import com.anas.ledgerwallet.ledger.dto.TransferRequest;
import com.anas.ledgerwallet.ledger.dto.TransferResponse;
import com.anas.ledgerwallet.ledger.event.TransactionEventPayload;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Event publishing end to end, against a real broker.
 *
 * <p>The load-bearing test here is the negative one: a rejected transaction must
 * produce no event at all. An audit log that records money that never moved is worse
 * than no audit log, because it cannot be told apart from a true one.
 */
class TransactionEventIT extends IntegrationTestBase {

    private static final String PASSWORD = "a-sufficiently-long-password";

    /** Generous: the assertion is what the outcome is, not how fast it arrives. */
    private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(30);

    /**
     * How long to wait before concluding that nothing was published. Long enough that
     * a slow-but-real event would have shown up, or the test proves nothing.
     */
    private static final Duration SILENCE_WINDOW = Duration.ofSeconds(8);

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private KafkaTemplate<String, TransactionEventPayload> kafkaTemplate;

    @Value("${app.kafka.transaction-topic}")
    private String transactionTopic;

    private String newUserToken() {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        restTemplate.postForEntity("/api/v1/auth/register",
                new RegisterRequest(email, PASSWORD, "Test User"), UserResponse.class);
        return restTemplate.postForEntity("/api/v1/auth/login",
                new LoginRequest(email, PASSWORD), AuthResponse.class).getBody().accessToken();
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private UUID newAccount(String token) {
        return restTemplate.exchange("/api/v1/accounts", HttpMethod.POST,
                new HttpEntity<>(bearer(token)), AccountResponse.class).getBody().id();
    }

    private ResponseEntity<TransactionResponse> deposit(
            String token, UUID accountId, String amount) {

        return restTemplate.exchange("/api/v1/accounts/" + accountId + "/deposit",
                HttpMethod.POST,
                new HttpEntity<>(new MoneyMovementRequest(new BigDecimal(amount), null),
                        bearer(token)),
                TransactionResponse.class);
    }

    private ResponseEntity<String> withdraw(String token, UUID accountId, String amount) {
        return restTemplate.exchange("/api/v1/accounts/" + accountId + "/withdraw",
                HttpMethod.POST,
                new HttpEntity<>(new MoneyMovementRequest(new BigDecimal(amount), null),
                        bearer(token)),
                String.class);
    }

    private List<AuditLogEntry> auditedFor(UUID transactionId) {
        return auditLogRepository.findByTransactionId(transactionId);
    }

    @Test
    @DisplayName("A committed deposit produces exactly one audited event")
    void committedDepositIsAudited() {
        String token = newUserToken();
        UUID accountId = newAccount(token);

        UUID transactionId = deposit(token, accountId, "120.00").getBody().transactionId();

        await().atMost(DELIVERY_TIMEOUT)
                .untilAsserted(() -> assertThat(auditedFor(transactionId)).hasSize(1));

        AuditLogEntry audited = auditedFor(transactionId).get(0);
        assertThat(audited.getType()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(audited.getAmount()).isEqualByComparingTo("120.00");
        assertThat(audited.getToAccountId()).isEqualTo(accountId);
        assertThat(audited.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(audited.getOccurredAt()).isNotNull();
    }

    @Test
    @DisplayName("A committed transfer is audited with both accounts and both balances")
    void committedTransferIsAudited() {
        String senderToken = newUserToken();
        UUID source = newAccount(senderToken);
        UUID destination = newAccount(newUserToken());
        deposit(senderToken, source, "200.00");

        UUID transactionId = restTemplate.exchange("/api/v1/transfers", HttpMethod.POST,
                new HttpEntity<>(
                        new TransferRequest(source, destination, new BigDecimal("60.00"), null),
                        bearer(senderToken)),
                TransferResponse.class).getBody().transactionId();

        await().atMost(DELIVERY_TIMEOUT)
                .untilAsserted(() -> assertThat(auditedFor(transactionId)).hasSize(1));

        AuditLogEntry audited = auditedFor(transactionId).get(0);
        assertThat(audited.getType()).isEqualTo(TransactionType.TRANSFER);
        assertThat(audited.getFromAccountId()).isEqualTo(source);
        assertThat(audited.getToAccountId()).isEqualTo(destination);
        assertThat(audited.getAmount()).isEqualByComparingTo("60.00");
    }

    @Test
    @DisplayName("A rejected withdrawal produces ZERO events")
    void rejectedWithdrawalPublishesNothing() {
        String token = newUserToken();
        UUID accountId = newAccount(token);
        UUID fundingTransaction = deposit(token, accountId, "50.00").getBody().transactionId();

        // Wait for the deposit's own event first, so the silence below cannot be the
        // consumer simply not having started yet.
        await().atMost(DELIVERY_TIMEOUT)
                .untilAsserted(() -> assertThat(auditedFor(fundingTransaction)).hasSize(1));

        long auditedBefore = auditLogRepository.count();

        ResponseEntity<String> rejected = withdraw(token, accountId, "50.01");
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        // The transaction rolled back, so AFTER_COMMIT never fired. If the send had
        // happened inside the transaction, the audit log would now hold a withdrawal
        // that never occurred — and nothing downstream could tell it was false.
        await().pollDelay(SILENCE_WINDOW)
                .atMost(SILENCE_WINDOW.plusSeconds(5))
                .untilAsserted(() -> assertThat(auditLogRepository.count())
                        .as("a rejected transaction must publish no event")
                        .isEqualTo(auditedBefore));
    }

    @Test
    @DisplayName("A rejected transfer produces ZERO events")
    void rejectedTransferPublishesNothing() {
        String senderToken = newUserToken();
        UUID source = newAccount(senderToken);
        UUID destination = newAccount(newUserToken());
        UUID fundingTransaction = deposit(senderToken, source, "10.00").getBody()
                .transactionId();

        await().atMost(DELIVERY_TIMEOUT)
                .untilAsserted(() -> assertThat(auditedFor(fundingTransaction)).hasSize(1));

        long auditedBefore = auditLogRepository.count();

        ResponseEntity<String> rejected = restTemplate.exchange(
                "/api/v1/transfers", HttpMethod.POST,
                new HttpEntity<>(
                        new TransferRequest(source, destination, new BigDecimal("999.00"), null),
                        bearer(senderToken)),
                String.class);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        await().pollDelay(SILENCE_WINDOW)
                .atMost(SILENCE_WINDOW.plusSeconds(5))
                .untilAsserted(() -> assertThat(auditLogRepository.count())
                        .isEqualTo(auditedBefore));
    }

    @Test
    @DisplayName("An idempotent replay does not publish a second event")
    void replayPublishesNothingNew() {
        String token = newUserToken();
        UUID accountId = newAccount(token);
        MoneyMovementRequest request =
                new MoneyMovementRequest(new BigDecimal("40.00"), "key-" + UUID.randomUUID());

        UUID transactionId = restTemplate.exchange(
                "/api/v1/accounts/" + accountId + "/deposit", HttpMethod.POST,
                new HttpEntity<>(request, bearer(token)), TransactionResponse.class)
                .getBody().transactionId();

        await().atMost(DELIVERY_TIMEOUT)
                .untilAsserted(() -> assertThat(auditedFor(transactionId)).hasSize(1));

        restTemplate.exchange("/api/v1/accounts/" + accountId + "/deposit", HttpMethod.POST,
                new HttpEntity<>(request, bearer(token)), TransactionResponse.class);

        // A retry moves no money, so it is not a second event to audit.
        await().pollDelay(SILENCE_WINDOW)
                .atMost(SILENCE_WINDOW.plusSeconds(5))
                .untilAsserted(() -> assertThat(auditedFor(transactionId)).hasSize(1));
    }

    @Test
    @DisplayName("The same event delivered twice is recorded once")
    void redeliveryDoesNotDuplicate() {
        UUID eventId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        TransactionEventPayload payload = new TransactionEventPayload(
                eventId, transactionId, TransactionType.TRANSFER, new BigDecimal("35.00"),
                UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("15.00"), new BigDecimal("85.00"),
                TransactionStatus.COMPLETED, Instant.now());

        // Publishing the same payload twice is what a Kafka redelivery looks like from
        // the consumer's side — a rebalance or a slow offset commit produces exactly
        // this. Replaying it directly avoids having to manipulate consumer offsets to
        // provoke the same condition.
        kafkaTemplate.send(transactionTopic, transactionId.toString(), payload);

        await().atMost(DELIVERY_TIMEOUT)
                .untilAsserted(() -> assertThat(auditedFor(transactionId)).hasSize(1));

        kafkaTemplate.send(transactionTopic, transactionId.toString(), payload);

        // Still one. Kafka guarantees at-least-once delivery; the unique index on
        // event_id is what turns that into exactly-once persistence.
        await().pollDelay(SILENCE_WINDOW)
                .atMost(SILENCE_WINDOW.plusSeconds(5))
                .untilAsserted(() -> assertThat(auditedFor(transactionId)).hasSize(1));
    }

    @Test
    @DisplayName("Every audited event is unique, so redelivery cannot duplicate a record")
    void auditRecordsAreUniquePerEvent() {
        String token = newUserToken();
        UUID accountId = newAccount(token);

        for (int i = 0; i < 3; i++) {
            deposit(token, accountId, "10.00");
        }

        await().atMost(DELIVERY_TIMEOUT).untilAsserted(() ->
                assertThat(auditLogRepository.findAll())
                        .filteredOn(entry -> entry.getToAccountId().equals(accountId))
                        .hasSize(3));

        List<UUID> eventIds = auditLogRepository.findAll().stream()
                .map(AuditLogEntry::getEventId)
                .toList();

        assertThat(eventIds).doesNotHaveDuplicates();
    }
}
