package com.anas.ledgerwallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.anas.ledgerwallet.account.Account;
import com.anas.ledgerwallet.account.AccountRepository;
import com.anas.ledgerwallet.account.dto.AccountResponse;
import com.anas.ledgerwallet.audit.AuditLogRepository;
import com.anas.ledgerwallet.auth.User;
import com.anas.ledgerwallet.auth.UserRepository;
import com.anas.ledgerwallet.auth.dto.AuthResponse;
import com.anas.ledgerwallet.auth.dto.LoginRequest;
import com.anas.ledgerwallet.auth.dto.RegisterRequest;
import com.anas.ledgerwallet.auth.dto.UserResponse;
import com.anas.ledgerwallet.common.dto.PageResponse;
import com.anas.ledgerwallet.ledger.EntryDirection;
import com.anas.ledgerwallet.ledger.LedgerEntry;
import com.anas.ledgerwallet.ledger.LedgerEntryRepository;
import com.anas.ledgerwallet.ledger.Transaction;
import com.anas.ledgerwallet.ledger.TransactionRepository;
import com.anas.ledgerwallet.ledger.TransactionType;
import com.anas.ledgerwallet.ledger.dto.MoneyMovementRequest;
import com.anas.ledgerwallet.ledger.dto.TransactionDetailResponse;
import com.anas.ledgerwallet.ledger.dto.TransactionResponse;
import com.anas.ledgerwallet.ledger.dto.TransferRequest;
import com.anas.ledgerwallet.ledger.dto.TransferResponse;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The whole user journey, in order, against a real PostgreSQL and a real Kafka.
 *
 * <p>Each step is its own test so that a run reports which step failed rather than
 * "the journey failed". They share state and therefore run in a fixed order: a later
 * step has nothing to assert until the earlier ones have happened.
 *
 * <p>The other integration tests each prove one rule in isolation. This one proves
 * they compose — that the account created in step 3 is the one the statement reports
 * in step 8, and that the money is still all there at the end.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FullJourneyIT extends IntegrationTestBase {

    private static final String PASSWORD = "a-sufficiently-long-password";
    private static final BigDecimal DEPOSIT = new BigDecimal("500.00");
    private static final BigDecimal TRANSFER = new BigDecimal("120.00");

    /** Generous: what matters is the outcome, not how fast the broker delivers. */
    private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(30);

    private static String aliceEmail;
    private static UUID aliceUserId;
    private static String aliceToken;
    private static String bobToken;
    private static UUID accountA;
    private static UUID accountB;
    private static UUID depositTransactionId;
    private static UUID transferTransactionId;

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private LedgerEntryRepository ledgerEntryRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private BigDecimal balanceOf(UUID accountId) {
        return accountRepository.findById(accountId).orElseThrow().getBalance();
    }

    /** Invariant 2: every entry ever written, summed, is exactly zero. */
    private void assertLedgerSumsToZero() {
        assertThat(ledgerEntryRepository.sumAllSignedAmounts())
                .as("Invariant: the whole ledger sums to zero")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    /** Invariant 3, and no negative balances. */
    private void assertReconciles(UUID accountId) {
        BigDecimal stored = balanceOf(accountId);

        assertThat(stored)
                .as("Invariant: stored balance equals the sum of the account's entries")
                .isEqualByComparingTo(ledgerEntryRepository.sumSignedAmountsForAccount(accountId));
        assertThat(stored)
                .as("Invariant: no balance is negative")
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    @Test
    @Order(1)
    @DisplayName("1. Register a user")
    void registerUser() {
        aliceEmail = "journey-" + UUID.randomUUID() + "@example.com";

        // Asked for as a String so the raw payload can be inspected: a DTO would
        // silently drop any field it does not declare, which is the very thing at risk.
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/register",
                new RegisterRequest(aliceEmail, PASSWORD, "Alice Journey"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody())
                .as("no credential material may appear in the response")
                .doesNotContain(PASSWORD)
                .doesNotContain("passwordHash")
                .doesNotContain("$2a$");

        User stored = userRepository.findByEmail(aliceEmail).orElseThrow();
        aliceUserId = stored.getId();

        assertThat(stored.getPasswordHash()).isNotEqualTo(PASSWORD);
        assertThat(stored.getPasswordHash()).startsWith("$2");
        assertThat(passwordEncoder.matches(PASSWORD, stored.getPasswordHash()))
                .as("the stored hash must verify against the original password")
                .isTrue();
    }

    @Test
    @Order(2)
    @DisplayName("2. Log in and use the token on a protected route")
    void logIn() {
        ResponseEntity<AuthResponse> loggedIn = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(aliceEmail, PASSWORD), AuthResponse.class);

        assertThat(loggedIn.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loggedIn.getBody().accessToken()).isNotBlank();
        assertThat(loggedIn.getBody().tokenType()).isEqualTo("Bearer");
        aliceToken = loggedIn.getBody().accessToken();

        ResponseEntity<UserResponse> me = restTemplate.exchange(
                "/api/v1/auth/me", HttpMethod.GET,
                new HttpEntity<>(bearer(aliceToken)), UserResponse.class);

        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody().email()).isEqualTo(aliceEmail);
        assertThat(me.getBody().id()).isEqualTo(aliceUserId);
    }

    @Test
    @Order(3)
    @DisplayName("3. Create account A")
    void createAccountA() {
        ResponseEntity<AccountResponse> created = restTemplate.exchange(
                "/api/v1/accounts", HttpMethod.POST,
                new HttpEntity<>(bearer(aliceToken)), AccountResponse.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().balance()).isEqualByComparingTo("0.00");
        accountA = created.getBody().id();

        // Ownership read from the database rather than trusted from the response.
        assertThat(accountRepository.findByOwnerIdOrderByCreatedAtAsc(aliceUserId))
                .extracting(Account::getId)
                .contains(accountA);
    }

    @Test
    @Order(4)
    @DisplayName("4. Create account B, owned by a second user")
    void createAccountB() {
        String bobEmail = "journey-bob-" + UUID.randomUUID() + "@example.com";
        restTemplate.postForEntity("/api/v1/auth/register",
                new RegisterRequest(bobEmail, PASSWORD, "Bob Journey"), UserResponse.class);
        bobToken = restTemplate.postForEntity("/api/v1/auth/login",
                new LoginRequest(bobEmail, PASSWORD), AuthResponse.class).getBody().accessToken();

        ResponseEntity<AccountResponse> created = restTemplate.exchange(
                "/api/v1/accounts", HttpMethod.POST,
                new HttpEntity<>(bearer(bobToken)), AccountResponse.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().balance()).isEqualByComparingTo("0.00");
        accountB = created.getBody().id();

        // A second user, so the transfer in step 6 crosses an ownership boundary
        // rather than moving money between two of one person's own wallets.
        assertThat(accountB).isNotEqualTo(accountA);
    }

    @Test
    @Order(5)
    @DisplayName("5. Deposit into A")
    void depositIntoA() {
        ResponseEntity<TransactionResponse> deposited = restTemplate.exchange(
                "/api/v1/accounts/" + accountA + "/deposit", HttpMethod.POST,
                new HttpEntity<>(new MoneyMovementRequest(DEPOSIT, "journey-deposit"),
                        bearer(aliceToken)),
                TransactionResponse.class);

        assertThat(deposited.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(deposited.getBody().balanceAfter()).isEqualByComparingTo(DEPOSIT);
        depositTransactionId = deposited.getBody().transactionId();

        assertThat(balanceOf(accountA))
                .as("the balance must rise by exactly the deposit")
                .isEqualByComparingTo(DEPOSIT);

        Transaction transaction = transactionRepository
                .findByIdWithEntries(depositTransactionId).orElseThrow();

        assertThat(transaction.getEntries())
                .as("a deposit writes exactly two ledger entries")
                .hasSize(2);
        assertThat(transaction.getEntries())
                .extracting(LedgerEntry::getDirection)
                .containsExactlyInAnyOrder(EntryDirection.DEBIT, EntryDirection.CREDIT);
        assertThat(transaction.getEntries().stream()
                .map(LedgerEntry::getSignedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add))
                .as("the pair sums to zero on its own")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @Order(6)
    @DisplayName("6. Transfer A to B")
    void transferAToB() {
        ResponseEntity<TransferResponse> transferred = restTemplate.exchange(
                "/api/v1/transfers", HttpMethod.POST,
                new HttpEntity<>(
                        new TransferRequest(accountA, accountB, TRANSFER, "journey-transfer"),
                        bearer(aliceToken)),
                TransferResponse.class);

        assertThat(transferred.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        transferTransactionId = transferred.getBody().transactionId();

        assertThat(transferred.getBody().fromBalanceAfter())
                .isEqualByComparingTo(DEPOSIT.subtract(TRANSFER));
        assertThat(balanceOf(accountA))
                .as("A is debited by exactly the amount")
                .isEqualByComparingTo(DEPOSIT.subtract(TRANSFER));
        assertThat(balanceOf(accountB))
                .as("B is credited by exactly the amount")
                .isEqualByComparingTo(TRANSFER);
    }

    @Test
    @Order(7)
    @DisplayName("7. Verify both balances, and the invariants")
    void verifyBalancesAndInvariants() {
        assertThat(balanceOf(accountA)).isEqualByComparingTo("380.00");
        assertThat(balanceOf(accountB)).isEqualByComparingTo("120.00");

        assertReconciles(accountA);
        assertReconciles(accountB);
        assertLedgerSumsToZero();
    }

    @Test
    @Order(8)
    @DisplayName("8. Fetch transaction history")
    void fetchHistory() {
        ResponseEntity<PageResponse<StatementRow>> statement = restTemplate.exchange(
                "/api/v1/accounts/" + accountA + "/transactions?page=0&size=20",
                HttpMethod.GET,
                new HttpEntity<>(bearer(aliceToken)),
                new ParameterizedTypeReference<PageResponse<StatementRow>>() {});

        assertThat(statement.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<StatementRow> rows = statement.getBody().content();

        assertThat(rows).as("both movements appear on A's statement").hasSize(2);
        // Newest first: the transfer happened after the deposit.
        assertThat(rows.get(0).transactionId()).isEqualTo(transferTransactionId);
        assertThat(rows.get(0).direction()).isEqualTo(EntryDirection.DEBIT);
        assertThat(rows.get(1).transactionId()).isEqualTo(depositTransactionId);
        assertThat(rows.get(1).direction()).isEqualTo(EntryDirection.CREDIT);

        // The same transfer is a credit on the other side of the boundary.
        ResponseEntity<PageResponse<StatementRow>> bobStatement = restTemplate.exchange(
                "/api/v1/accounts/" + accountB + "/transactions",
                HttpMethod.GET,
                new HttpEntity<>(bearer(bobToken)),
                new ParameterizedTypeReference<PageResponse<StatementRow>>() {});

        assertThat(bobStatement.getBody().content()).hasSize(1);
        assertThat(bobStatement.getBody().content().get(0).direction())
                .isEqualTo(EntryDirection.CREDIT);

        // And the transaction itself shows both sides of the double entry.
        ResponseEntity<TransactionDetailResponse> detail = restTemplate.exchange(
                "/api/v1/transactions/" + transferTransactionId, HttpMethod.GET,
                new HttpEntity<>(bearer(aliceToken)), TransactionDetailResponse.class);

        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(detail.getBody().type()).isEqualTo(TransactionType.TRANSFER);
        assertThat(detail.getBody().entries()).hasSize(2);
    }

    @Test
    @Order(9)
    @DisplayName("9. Verify Kafka events, then the invariants again")
    void verifyKafkaEvents() {
        // The audit consumer writes one row per event and is idempotent on eventId,
        // while every publication carries a fresh eventId. So one row per transaction
        // means exactly one event was published for it — no more, no fewer.
        await().atMost(DELIVERY_TIMEOUT).untilAsserted(() -> {
            assertThat(auditLogRepository.findByTransactionId(depositTransactionId))
                    .as("exactly one event for the deposit")
                    .hasSize(1);
            assertThat(auditLogRepository.findByTransactionId(transferTransactionId))
                    .as("exactly one event for the transfer")
                    .hasSize(1);
        });

        assertThat(auditLogRepository.findByTransactionId(transferTransactionId).get(0)
                .getAmount())
                .as("the audited amount matches what moved")
                .isEqualByComparingTo(TRANSFER);

        assertReconciles(accountA);
        assertReconciles(accountB);
        assertLedgerSumsToZero();
    }

    /**
     * A statement row as the wire sees it.
     *
     * <p>Declared here rather than deserialising into {@code StatementEntryResponse}
     * so the test reads the JSON the API actually returns, and does not quietly pass
     * because both sides share one class.
     */
    private record StatementRow(
            UUID entryId,
            UUID transactionId,
            TransactionType type,
            EntryDirection direction,
            BigDecimal amount,
            BigDecimal balanceAfter,
            Object counterparty,
            String createdAt) {
    }
}
