package com.anas.ledgerwallet.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.anas.ledgerwallet.IntegrationTestBase;
import com.anas.ledgerwallet.account.AccountRepository;
import com.anas.ledgerwallet.account.dto.AccountResponse;
import com.anas.ledgerwallet.auth.dto.AuthResponse;
import com.anas.ledgerwallet.auth.dto.LoginRequest;
import com.anas.ledgerwallet.auth.dto.RegisterRequest;
import com.anas.ledgerwallet.auth.dto.UserResponse;
import com.anas.ledgerwallet.common.error.ErrorResponse;
import com.anas.ledgerwallet.ledger.dto.MoneyMovementRequest;
import com.anas.ledgerwallet.ledger.dto.TransactionResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Deposits and withdrawals against a real PostgreSQL, including the concurrency
 * behaviour that {@code @Version} exists to provide.
 */
class MoneyMovementIT extends IntegrationTestBase {

    private static final String PASSWORD = "a-sufficiently-long-password";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private AccountRepository accountRepository;
    @Autowired private LedgerEntryRepository ledgerEntryRepository;

    private String newUserToken() {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        restTemplate.postForEntity("/api/v1/auth/register",
                new RegisterRequest(email, PASSWORD, "Test User"), UserResponse.class);
        ResponseEntity<AuthResponse> loggedIn = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, PASSWORD), AuthResponse.class);
        return loggedIn.getBody().accessToken();
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

    private <T> ResponseEntity<T> move(
            String token, UUID accountId, String operation, String amount, Class<T> type) {

        return move(token, accountId, operation, new MoneyMovementRequest(
                new BigDecimal(amount), null), type);
    }

    private <T> ResponseEntity<T> move(
            String token, UUID accountId, String operation,
            MoneyMovementRequest request, Class<T> type) {

        return restTemplate.exchange(
                "/api/v1/accounts/" + accountId + "/" + operation,
                HttpMethod.POST,
                new HttpEntity<>(request, bearer(token)),
                type);
    }

    private BigDecimal balanceOf(UUID accountId) {
        return accountRepository.findById(accountId).orElseThrow().getBalance();
    }

    /** Every entry ever written, summed. Must be exactly zero (prd.md, Invariant 2). */
    private void assertLedgerBalances() {
        assertThat(ledgerEntryRepository.sumAllSignedAmounts())
                .as("system-wide ledger must sum to zero")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    /** An account's stored balance must equal the sum of its own entries (Invariant 3). */
    private void assertReconciles(UUID accountId) {
        assertThat(balanceOf(accountId))
                .as("stored balance must equal the sum of the account's ledger entries")
                .isEqualByComparingTo(ledgerEntryRepository.sumSignedAmountsForAccount(accountId));
    }

    @Test
    @DisplayName("Deposit raises the balance and both invariants hold")
    void depositRaisesBalance() {
        String token = newUserToken();
        UUID accountId = newAccount(token);

        ResponseEntity<TransactionResponse> response =
                move(token, accountId, "deposit", "150.00", TransactionResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().balanceAfter()).isEqualByComparingTo("150.00");
        assertThat(balanceOf(accountId)).isEqualByComparingTo("150.00");
        assertReconciles(accountId);
        assertLedgerBalances();
    }

    @Test
    @DisplayName("Withdrawal lowers the balance and both invariants hold")
    void withdrawalLowersBalance() {
        String token = newUserToken();
        UUID accountId = newAccount(token);
        move(token, accountId, "deposit", "150.00", TransactionResponse.class);

        ResponseEntity<TransactionResponse> response =
                move(token, accountId, "withdraw", "60.00", TransactionResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(balanceOf(accountId)).isEqualByComparingTo("90.00");
        assertReconciles(accountId);
        assertLedgerBalances();
    }

    @Test
    @DisplayName("Withdrawing more than the balance returns 422 and changes nothing")
    void insufficientFundsRejected() {
        String token = newUserToken();
        UUID accountId = newAccount(token);
        move(token, accountId, "deposit", "50.00", TransactionResponse.class);

        ResponseEntity<ErrorResponse> response =
                move(token, accountId, "withdraw", "50.01", ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().code()).isEqualTo("INSUFFICIENT_FUNDS");
        // The refusal must not report how much the account actually holds.
        assertThat(response.getBody().message()).doesNotContain("50");
        assertThat(balanceOf(accountId)).isEqualByComparingTo("50.00");
        assertReconciles(accountId);
        assertLedgerBalances();
    }

    @Test
    @DisplayName("A zero or negative amount is rejected by validation")
    void rejectsNonPositiveAmounts() {
        String token = newUserToken();
        UUID accountId = newAccount(token);

        assertThat(move(token, accountId, "deposit",
                new MoneyMovementRequest(BigDecimal.ZERO, null), ErrorResponse.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(move(token, accountId, "deposit",
                new MoneyMovementRequest(new BigDecimal("-10.00"), null), ErrorResponse.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // A negative deposit would otherwise be a withdrawal that skips the funds check.
        assertThat(balanceOf(accountId)).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("Depositing into another user's account returns 403")
    void cannotMoveMoneyInAnotherUsersAccount() {
        String ownerToken = newUserToken();
        String intruderToken = newUserToken();
        UUID victimAccount = newAccount(ownerToken);

        ResponseEntity<ErrorResponse> response =
                move(intruderToken, victimAccount, "deposit", "10.00", ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(balanceOf(victimAccount)).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("Withdrawing from another user's account returns 403")
    void cannotWithdrawFromAnotherUsersAccount() {
        String ownerToken = newUserToken();
        String intruderToken = newUserToken();
        UUID victimAccount = newAccount(ownerToken);
        move(ownerToken, victimAccount, "deposit", "200.00", TransactionResponse.class);

        ResponseEntity<ErrorResponse> response =
                move(intruderToken, victimAccount, "withdraw", "50.00", ErrorResponse.class);

        // 403 rather than 422: an intruder must not learn whether the account could
        // have afforded it. The service refuses on ownership before reading a balance.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(balanceOf(victimAccount)).isEqualByComparingTo("200.00");
        assertReconciles(victimAccount);
    }

    @Test
    @DisplayName("Money movement endpoints require authentication")
    void moneyMovementEndpointsRequireAuthentication() {
        UUID accountId = newAccount(newUserToken());
        MoneyMovementRequest request = new MoneyMovementRequest(new BigDecimal("10.00"), null);

        // No Authorization header at all: the filter chain must refuse both before any
        // handler runs. The deny-by-default rule is asserted generically elsewhere;
        // these two paths move money, so they are worth naming.
        assertThat(restTemplate.postForEntity(
                "/api/v1/accounts/" + accountId + "/deposit", request, String.class)
                .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(restTemplate.postForEntity(
                "/api/v1/accounts/" + accountId + "/withdraw", request, String.class)
                .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(balanceOf(accountId)).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("Repeating an idempotency key does not apply the movement twice")
    void idempotencyKeyPreventsDoubleApply() {
        String token = newUserToken();
        UUID accountId = newAccount(token);
        MoneyMovementRequest request =
                new MoneyMovementRequest(new BigDecimal("75.00"), "key-" + UUID.randomUUID());

        ResponseEntity<TransactionResponse> first =
                move(token, accountId, "deposit", request, TransactionResponse.class);
        ResponseEntity<TransactionResponse> replay =
                move(token, accountId, "deposit", request, TransactionResponse.class);

        assertThat(first.getBody().transactionId()).isEqualTo(replay.getBody().transactionId());
        assertThat(balanceOf(accountId)).isEqualByComparingTo("75.00");
        assertReconciles(accountId);
        assertLedgerBalances();
    }

    @Test
    @DisplayName("Concurrent withdrawals never drive the balance negative")
    void concurrentWithdrawalsNeverGoNegative() throws Exception {
        String token = newUserToken();
        UUID accountId = newAccount(token);
        move(token, accountId, "deposit", "100.00", TransactionResponse.class);

        // Ten threads each try to take 30 from a balance of 100. Arithmetic allows at
        // most three; without optimistic locking, several would read 100, all pass the
        // funds check, and the balance would end up wrong or negative.
        int threads = 10;
        BigDecimal each = new BigDecimal("30.00");

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startLine = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger conflicted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        try {
            List<Future<?>> attempts = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                attempts.add(pool.submit(() -> {
                    startLine.await();
                    ResponseEntity<String> response = move(
                            token, accountId, "withdraw",
                            new MoneyMovementRequest(each, null), String.class);

                    switch (response.getStatusCode().value()) {
                        case 201 -> succeeded.incrementAndGet();
                        case 409 -> conflicted.incrementAndGet();
                        case 422 -> rejected.incrementAndGet();
                        default -> throw new AssertionError(
                                "Unexpected status " + response.getStatusCode());
                    }
                    return null;
                }));
            }

            // Released together so the requests genuinely overlap.
            startLine.countDown();
            for (Future<?> attempt : attempts) {
                attempt.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // Every request got a definite answer — none silently vanished.
        assertThat(succeeded.get() + conflicted.get() + rejected.get()).isEqualTo(threads);

        // Arithmetic ceiling: 100 / 30 = 3.
        assertThat(succeeded.get()).isBetween(1, 3);

        BigDecimal expected =
                new BigDecimal("100.00").subtract(each.multiply(new BigDecimal(succeeded.get())));
        assertThat(balanceOf(accountId)).isEqualByComparingTo(expected);

        // The point of the whole exercise.
        assertThat(balanceOf(accountId)).isGreaterThanOrEqualTo(BigDecimal.ZERO);

        assertReconciles(accountId);
        assertLedgerBalances();
    }

    @Test
    @DisplayName("Simultaneous deposit and withdrawal on one account do not deadlock")
    void mixedDepositAndWithdrawalDoNotDeadlock() throws Exception {
        String token = newUserToken();
        UUID accountId = newAccount(token);
        move(token, accountId, "deposit", "100.00", TransactionResponse.class);

        // A deposit posts (system, user) and a withdrawal posts (user, system) — the
        // same two rows in opposite orders. Before movements were applied in a fixed
        // id order, this pair could deadlock in PostgreSQL and surface as a 500. The
        // Phase 4 tests never mixed the two operations, so nothing caught it.
        int rounds = 6;
        ExecutorService pool = Executors.newFixedThreadPool(rounds * 2);
        CountDownLatch startLine = new CountDownLatch(1);
        AtomicInteger unexpected = new AtomicInteger();

        try {
            List<Future<?>> attempts = new java.util.ArrayList<>();
            for (int i = 0; i < rounds; i++) {
                attempts.add(pool.submit(() -> {
                    startLine.await();
                    record(move(token, accountId, "deposit",
                            new MoneyMovementRequest(new BigDecimal("10.00"), null),
                            String.class), unexpected);
                    return null;
                }));
                attempts.add(pool.submit(() -> {
                    startLine.await();
                    record(move(token, accountId, "withdraw",
                            new MoneyMovementRequest(new BigDecimal("10.00"), null),
                            String.class), unexpected);
                    return null;
                }));
            }
            startLine.countDown();
            for (Future<?> attempt : attempts) {
                attempt.get(90, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(unexpected.get())
                .as("no request may fail with a server error")
                .isZero();
        assertThat(balanceOf(accountId)).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertReconciles(accountId);
        assertLedgerBalances();
    }

    /** 201 committed, 409 lost the race, 422 outrun. Anything else is a real defect. */
    private void record(ResponseEntity<String> response, AtomicInteger unexpected) {
        if (!List.of(201, 409, 422).contains(response.getStatusCode().value())) {
            unexpected.incrementAndGet();
        }
    }

    @Test
    @DisplayName("Concurrent deposits all land, and the ledger still balances")
    void concurrentDepositsAllReconcile() throws Exception {
        String token = newUserToken();
        UUID accountId = newAccount(token);

        int threads = 8;
        BigDecimal each = new BigDecimal("10.00");

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startLine = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();

        try {
            List<Future<?>> attempts = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                attempts.add(pool.submit(() -> {
                    startLine.await();
                    ResponseEntity<String> response = move(
                            token, accountId, "deposit",
                            new MoneyMovementRequest(each, null), String.class);
                    if (response.getStatusCode().value() == 201) {
                        succeeded.incrementAndGet();
                    }
                    return null;
                }));
            }
            startLine.countDown();
            for (Future<?> attempt : attempts) {
                attempt.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // Whatever mix of successes and conflicts occurred, the stored balance must
        // account for exactly the deposits that committed — no lost updates.
        assertThat(balanceOf(accountId))
                .isEqualByComparingTo(each.multiply(new BigDecimal(succeeded.get())));
        assertReconciles(accountId);
        assertLedgerBalances();
    }
}
