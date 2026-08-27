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
import com.anas.ledgerwallet.ledger.dto.TransferRequest;
import com.anas.ledgerwallet.ledger.dto.TransferResponse;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Transfers end to end against real PostgreSQL, including the concurrency behaviour
 * that is the whole point of the project.
 */
class TransferIT extends IntegrationTestBase {

    private static final String PASSWORD = "a-sufficiently-long-password";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private AccountRepository accountRepository;
    @Autowired private LedgerEntryRepository ledgerEntryRepository;

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

    private UUID fundedAccount(String token, String amount) {
        UUID accountId = newAccount(token);
        restTemplate.exchange("/api/v1/accounts/" + accountId + "/deposit", HttpMethod.POST,
                new HttpEntity<>(new MoneyMovementRequest(new BigDecimal(amount), null),
                        bearer(token)),
                TransactionResponse.class);
        return accountId;
    }

    private <T> ResponseEntity<T> transfer(
            String token, TransferRequest request, Class<T> type) {

        return restTemplate.exchange("/api/v1/transfers", HttpMethod.POST,
                new HttpEntity<>(request, bearer(token)), type);
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
                .as("stored balance must equal the sum of this account's ledger entries")
                .isEqualByComparingTo(ledgerEntryRepository.sumSignedAmountsForAccount(accountId));
    }

    @Test
    @DisplayName("A transfer moves money between two users and conserves the total")
    void transferBetweenUsers() {
        String senderToken = newUserToken();
        String recipientToken = newUserToken();
        UUID source = fundedAccount(senderToken, "200.00");
        UUID destination = newAccount(recipientToken);

        ResponseEntity<TransferResponse> response = transfer(senderToken,
                new TransferRequest(source, destination, new BigDecimal("75.00"), null),
                TransferResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().fromBalanceAfter()).isEqualByComparingTo("125.00");
        assertThat(balanceOf(source)).isEqualByComparingTo("125.00");
        assertThat(balanceOf(destination)).isEqualByComparingTo("75.00");

        assertReconciles(source);
        assertReconciles(destination);
        assertLedgerBalances();
    }

    @Test
    @DisplayName("Transferring more than the balance returns 422 and moves nothing")
    void insufficientFundsRejected() {
        String token = newUserToken();
        UUID source = fundedAccount(token, "50.00");
        UUID destination = newAccount(newUserToken());

        ResponseEntity<ErrorResponse> response = transfer(token,
                new TransferRequest(source, destination, new BigDecimal("50.01"), null),
                ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().code()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(balanceOf(source)).isEqualByComparingTo("50.00");
        assertThat(balanceOf(destination)).isEqualByComparingTo("0.00");
        assertLedgerBalances();
    }

    @Test
    @DisplayName("Transferring to the same account is rejected with 400")
    void selfTransferRejected() {
        String token = newUserToken();
        UUID account = fundedAccount(token, "100.00");

        ResponseEntity<ErrorResponse> response = transfer(token,
                new TransferRequest(account, account, new BigDecimal("10.00"), null),
                ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("SELF_TRANSFER_NOT_ALLOWED");
        assertThat(balanceOf(account)).isEqualByComparingTo("100.00");
        assertLedgerBalances();
    }

    @Test
    @DisplayName("Sending from an account you do not own returns 403")
    void cannotSendFromAnotherUsersAccount() {
        String ownerToken = newUserToken();
        String intruderToken = newUserToken();
        UUID victimAccount = fundedAccount(ownerToken, "500.00");
        UUID intruderAccount = newAccount(intruderToken);

        ResponseEntity<ErrorResponse> response = transfer(intruderToken,
                new TransferRequest(victimAccount, intruderAccount,
                        new BigDecimal("500.00"), null),
                ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(balanceOf(victimAccount)).isEqualByComparingTo("500.00");
        assertThat(balanceOf(intruderAccount)).isEqualByComparingTo("0.00");
        assertLedgerBalances();
    }

    @Test
    @DisplayName("An unknown destination returns 404")
    void unknownDestinationRejected() {
        String token = newUserToken();
        UUID source = fundedAccount(token, "100.00");

        ResponseEntity<ErrorResponse> response = transfer(token,
                new TransferRequest(source, UUID.randomUUID(), new BigDecimal("10.00"), null),
                ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(balanceOf(source)).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("The system account cannot be used as a destination")
    void systemAccountNotAddressable() {
        String token = newUserToken();
        UUID source = fundedAccount(token, "100.00");

        ResponseEntity<ErrorResponse> response = transfer(token,
                new TransferRequest(source, LedgerService.SYSTEM_ACCOUNT_ID,
                        new BigDecimal("10.00"), null),
                ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(balanceOf(source)).isEqualByComparingTo("100.00");
        assertLedgerBalances();
    }

    @Test
    @DisplayName("Repeating an idempotency key does not transfer twice")
    void idempotencyKeyPreventsDoubleTransfer() {
        String token = newUserToken();
        UUID source = fundedAccount(token, "100.00");
        UUID destination = newAccount(newUserToken());
        TransferRequest request = new TransferRequest(
                source, destination, new BigDecimal("40.00"), "tk-" + UUID.randomUUID());

        ResponseEntity<TransferResponse> first =
                transfer(token, request, TransferResponse.class);
        ResponseEntity<TransferResponse> replay =
                transfer(token, request, TransferResponse.class);

        assertThat(first.getBody().transactionId()).isEqualTo(replay.getBody().transactionId());
        assertThat(balanceOf(source)).isEqualByComparingTo("60.00");
        assertThat(balanceOf(destination)).isEqualByComparingTo("40.00");
        assertReconciles(source);
        assertReconciles(destination);
        assertLedgerBalances();
    }

    @Test
    @DisplayName("Two simultaneous transfers from one account never overdraw it")
    void concurrentTransfersFromSameAccount() throws Exception {
        String token = newUserToken();
        UUID source = fundedAccount(token, "100.00");
        UUID first = newAccount(newUserToken());
        UUID second = newAccount(newUserToken());

        // Two transfers of 60 from a balance of 100: at most one can succeed.
        List<ResponseEntity<String>> results = fireTogether(List.of(
                () -> transfer(token,
                        new TransferRequest(source, first, new BigDecimal("60.00"), null),
                        String.class),
                () -> transfer(token,
                        new TransferRequest(source, second, new BigDecimal("60.00"), null),
                        String.class)));

        long succeeded = results.stream()
                .filter(r -> r.getStatusCode() == HttpStatus.CREATED)
                .count();

        assertThat(succeeded).isEqualTo(1);
        assertThat(balanceOf(source)).isEqualByComparingTo("40.00");
        assertThat(balanceOf(source)).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertReconciles(source);
        assertLedgerBalances();
    }

    @Test
    @DisplayName("Simultaneous transfers in opposite directions both settle without deadlock")
    void oppositeDirectionTransfersDoNotDeadlock() throws Exception {
        String tokenA = newUserToken();
        String tokenB = newUserToken();
        UUID accountA = fundedAccount(tokenA, "100.00");
        UUID accountB = fundedAccount(tokenB, "100.00");

        // A→B and B→A at the same moment. Under pessimistic locking this is the
        // classic deadlock; with optimistic locking the loser simply retries.
        List<ResponseEntity<String>> results = fireTogether(List.of(
                () -> transfer(tokenA,
                        new TransferRequest(accountA, accountB, new BigDecimal("30.00"), null),
                        String.class),
                () -> transfer(tokenB,
                        new TransferRequest(accountB, accountA, new BigDecimal("20.00"), null),
                        String.class)));

        // Whatever the interleaving, no request may hang or fail unexpectedly.
        assertThat(results).allSatisfy(r -> assertThat(r.getStatusCode().value()).isIn(201, 409));

        assertThat(balanceOf(accountA).add(balanceOf(accountB)))
                .as("total across both accounts is unchanged")
                .isEqualByComparingTo("200.00");
        assertReconciles(accountA);
        assertReconciles(accountB);
        assertLedgerBalances();
    }

    @RepeatedTest(value = 3, name = "pool transfer storm {currentRepetition}/{totalRepetitions}")
    @DisplayName("Concurrent transfers across a pool conserve money and never go negative")
    void concurrentTransfersAcrossPool() throws Exception {
        String token = newUserToken();
        int accountCount = 4;
        BigDecimal startingBalance = new BigDecimal("100.00");

        List<UUID> pool = new ArrayList<>();
        for (int i = 0; i < accountCount; i++) {
            pool.add(fundedAccount(token, startingBalance.toPlainString()));
        }
        BigDecimal totalBefore = startingBalance.multiply(new BigDecimal(accountCount));

        int threads = 12;
        BigDecimal each = new BigDecimal("10.00");
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLine = new CountDownLatch(1);
        AtomicInteger unexpected = new AtomicInteger();

        try {
            List<Future<?>> attempts = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                attempts.add(executor.submit(() -> {
                    startLine.await();

                    int fromIndex = ThreadLocalRandom.current().nextInt(accountCount);
                    int toIndex = (fromIndex
                            + 1
                            + ThreadLocalRandom.current().nextInt(accountCount - 1))
                            % accountCount;

                    ResponseEntity<String> response = transfer(token,
                            new TransferRequest(pool.get(fromIndex), pool.get(toIndex),
                                    each, null),
                            String.class);

                    // 201 committed, 409 lost every retry, 422 was outrun by another
                    // transfer draining the source. Anything else is a real defect.
                    if (!List.of(201, 409, 422).contains(response.getStatusCode().value())) {
                        unexpected.incrementAndGet();
                    }
                    return null;
                }));
            }

            startLine.countDown();
            for (Future<?> attempt : attempts) {
                attempt.get(90, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(unexpected.get()).as("no unexpected status codes").isZero();

        BigDecimal totalAfter = pool.stream()
                .map(this::balanceOf)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // The three invariants, under real contention.
        assertThat(totalAfter)
                .as("money is neither created nor destroyed")
                .isEqualByComparingTo(totalBefore);
        pool.forEach(id -> assertThat(balanceOf(id))
                .as("no account went negative")
                .isGreaterThanOrEqualTo(BigDecimal.ZERO));
        pool.forEach(this::assertReconciles);
        assertLedgerBalances();
    }

    /** Runs the given calls simultaneously, released together from one latch. */
    private List<ResponseEntity<String>> fireTogether(
            List<java.util.concurrent.Callable<ResponseEntity<String>>> calls) throws Exception {

        ExecutorService executor = Executors.newFixedThreadPool(calls.size());
        CountDownLatch startLine = new CountDownLatch(1);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();

        try {
            for (java.util.concurrent.Callable<ResponseEntity<String>> call : calls) {
                futures.add(executor.submit(() -> {
                    startLine.await();
                    return call.call();
                }));
            }
            startLine.countDown();

            List<ResponseEntity<String>> results = new ArrayList<>();
            for (Future<ResponseEntity<String>> future : futures) {
                results.add(future.get(90, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }
}
