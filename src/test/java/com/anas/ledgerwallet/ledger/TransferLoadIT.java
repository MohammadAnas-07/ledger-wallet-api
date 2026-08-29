package com.anas.ledgerwallet.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.anas.ledgerwallet.IntegrationTestBase;
import com.anas.ledgerwallet.account.AccountRepository;
import com.anas.ledgerwallet.account.dto.AccountResponse;
import com.anas.ledgerwallet.auth.dto.AuthResponse;
import com.anas.ledgerwallet.auth.dto.LoginRequest;
import com.anas.ledgerwallet.auth.dto.RegisterRequest;
import com.anas.ledgerwallet.auth.dto.UserResponse;
import com.anas.ledgerwallet.ledger.dto.MoneyMovementRequest;
import com.anas.ledgerwallet.ledger.dto.TransferRequest;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Sustained concurrent traffic against the real HTTP endpoints, with the ledger
 * invariants asserted against the database afterwards.
 *
 * <p>Different in kind from {@code TransferIT}'s concurrency tests. Those fire a fixed
 * burst at a chosen conflict — two transfers over the same account, opposite
 * directions — to prove a specific race is handled. This one runs unstructured traffic
 * for a while and then asks whether the books still balance, which is the question a
 * hand-picked scenario cannot answer: it is the conflicts nobody thought to write a
 * test for that corrupt a ledger.
 *
 * <p>It also produces the numbers Phase 8 needs to decide about the system account.
 * The two scenarios differ in exactly one respect — whether the movements share the
 * system account — so the gap between their conflict rates is the cost of that row,
 * measured rather than assumed.
 */
class TransferLoadIT extends IntegrationTestBase {

    private static final Logger log = LoggerFactory.getLogger(TransferLoadIT.class);

    private static final String PASSWORD = "a-sufficiently-long-password";

    /** Enough writers to overlap constantly without drowning a laptop's connection pool. */
    private static final int THREADS = 12;
    private static final int ACCOUNTS = 8;

    /** Long enough to cover thousands of transactions; short enough to sit in a suite. */
    private static final Duration ROUND = Duration.ofSeconds(6);

    /**
     * Repeated, because a race that appears once in twenty runs is still a race
     * (rules.md 3.2). Each round asserts the invariants on its own.
     */
    private static final int ROUNDS = 2;

    private static final BigDecimal OPENING_BALANCE = new BigDecimal("2000.00");
    private static final BigDecimal TRANSFER_AMOUNT = new BigDecimal("1.00");
    private static final BigDecimal DEPOSIT_AMOUNT = new BigDecimal("1.00");

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private AccountRepository accountRepository;
    @Autowired private LedgerEntryRepository ledgerEntryRepository;

    /** One user, their token, and the account they own. */
    private record Client(String token, UUID accountId) {
    }

    /** What a burst of traffic produced, by outcome. */
    private record Outcome(
            AtomicInteger accepted,
            AtomicInteger conflicted,
            AtomicInteger refused,
            AtomicInteger unexpected) {

        Outcome() {
            this(new AtomicInteger(), new AtomicInteger(), new AtomicInteger(), new AtomicInteger());
        }

        int total() {
            return accepted.get() + conflicted.get() + refused.get() + unexpected.get();
        }

        double conflictRatePercent() {
            return total() == 0 ? 0 : (conflicted.get() * 100.0) / total();
        }

        void record(HttpStatus status) {
            switch (status) {
                case CREATED -> accepted.incrementAndGet();
                case CONFLICT -> conflicted.incrementAndGet();
                case UNPROCESSABLE_ENTITY -> refused.incrementAndGet();
                default -> unexpected.incrementAndGet();
            }
        }
    }

    private Client newFundedClient() {
        String email = "load-" + UUID.randomUUID() + "@example.com";
        restTemplate.postForEntity("/api/v1/auth/register",
                new RegisterRequest(email, PASSWORD, "Load User"), UserResponse.class);
        String token = restTemplate.postForEntity("/api/v1/auth/login",
                new LoginRequest(email, PASSWORD), AuthResponse.class).getBody().accessToken();

        UUID accountId = restTemplate.exchange("/api/v1/accounts", HttpMethod.POST,
                new HttpEntity<>(bearer(token)), AccountResponse.class).getBody().id();

        deposit(new Client(token, accountId), OPENING_BALANCE);
        return new Client(token, accountId);
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private HttpStatus deposit(Client client, BigDecimal amount) {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/accounts/" + client.accountId() + "/deposit",
                HttpMethod.POST,
                new HttpEntity<>(
                        new MoneyMovementRequest(amount, UUID.randomUUID().toString()),
                        bearer(client.token())),
                String.class);

        return HttpStatus.valueOf(response.getStatusCode().value());
    }

    private HttpStatus transfer(Client from, Client to) {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/transfers",
                HttpMethod.POST,
                new HttpEntity<>(
                        new TransferRequest(from.accountId(), to.accountId(), TRANSFER_AMOUNT,
                                UUID.randomUUID().toString()),
                        bearer(from.token())),
                String.class);

        return HttpStatus.valueOf(response.getStatusCode().value());
    }

    /**
     * Runs {@code work} on every thread until the round is over, all starting together,
     * and tallies the status each call returned.
     */
    private Outcome runForOneRound(IntFunction<HttpStatus> work) throws Exception {
        Outcome outcome = new Outcome();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(THREADS);

        for (int thread = 0; thread < THREADS; thread++) {
            int index = thread;
            pool.submit(() -> {
                try {
                    // Released together, so the threads genuinely overlap instead of
                    // trickling in as each one is scheduled.
                    start.await();
                    long deadline = System.nanoTime() + ROUND.toNanos();
                    while (System.nanoTime() < deadline) {
                        outcome.record(work.apply(index));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finished.countDown();
                }
            });
        }

        start.countDown();
        assertThat(finished.await(2, TimeUnit.MINUTES))
                .as("every load thread should finish its round")
                .isTrue();
        pool.shutdownNow();

        return outcome;
    }

    /** Every entry ever written, summed. Must be exactly zero (prd.md, Invariant 2). */
    private void assertLedgerSumsToZero() {
        assertThat(ledgerEntryRepository.sumAllSignedAmounts())
                .as("system-wide ledger must sum to zero")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    /** Every account reconciles with its own entries, and no user account is negative. */
    private void assertAccountsReconcile(List<Client> clients) {
        for (Client client : clients) {
            BigDecimal stored = accountRepository.findById(client.accountId())
                    .orElseThrow().getBalance();

            assertThat(stored)
                    .as("stored balance must equal the sum of the account's ledger entries")
                    .isEqualByComparingTo(
                            ledgerEntryRepository.sumSignedAmountsForAccount(client.accountId()));
            assertThat(stored)
                    .as("no account may go negative")
                    .isGreaterThanOrEqualTo(BigDecimal.ZERO);
        }
    }

    private BigDecimal totalHeldBy(List<Client> clients) {
        return clients.stream()
                .map(client -> accountRepository.findById(client.accountId())
                        .orElseThrow().getBalance())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    @DisplayName("Sustained transfers leave the ledger balanced and no money created or lost")
    void sustainedTransfersKeepTheLedgerBalanced() throws Exception {
        List<Client> clients = new ArrayList<>();
        for (int i = 0; i < ACCOUNTS; i++) {
            clients.add(newFundedClient());
        }

        BigDecimal beforeLoad = totalHeldBy(clients);
        assertThat(beforeLoad).isEqualByComparingTo(OPENING_BALANCE.multiply(new BigDecimal(ACCOUNTS)));

        for (int round = 1; round <= ROUNDS; round++) {
            Outcome outcome = runForOneRound(threadIndex -> {
                int fromIndex = ThreadLocalRandom.current().nextInt(clients.size());
                int toIndex = ThreadLocalRandom.current().nextInt(clients.size() - 1);
                // Skip over the source rather than retrying, so a self-transfer is
                // impossible without biasing which pair comes up.
                if (toIndex >= fromIndex) {
                    toIndex++;
                }
                return transfer(clients.get(fromIndex), clients.get(toIndex));
            });

            log.info("Transfer load round {}: {} requests, {} accepted, {} conflicted ({}%), "
                            + "{} refused, {} unexpected",
                    round, outcome.total(), outcome.accepted().get(), outcome.conflicted().get(),
                    String.format("%.2f", outcome.conflictRatePercent()),
                    outcome.refused().get(), outcome.unexpected().get());

            assertThat(outcome.total())
                    .as("the round should actually have produced traffic")
                    .isGreaterThan(0);
            assertThat(outcome.unexpected().get())
                    .as("every response should be a known outcome, never a 500")
                    .isZero();

            assertLedgerSumsToZero();
            assertAccountsReconcile(clients);

            // Transfers only move money between these accounts, so the total they hold
            // cannot change however the requests interleaved. A lost update would show
            // up here as money that quietly appeared or vanished.
            assertThat(totalHeldBy(clients))
                    .as("transfers must not create or destroy money")
                    .isEqualByComparingTo(beforeLoad);
        }
    }

    @Test
    @DisplayName("Sustained deposits into different accounts still contend on the system account")
    void sustainedDepositsMeasureSystemAccountContention() throws Exception {
        // One account per thread, so no two threads ever touch the same user account.
        // Every conflict measured here is therefore caused by the one row they do
        // share: the system account that every deposit posts its counter-entry against.
        List<Client> clients = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            clients.add(newFundedClient());
        }

        AtomicInteger[] acceptedPerAccount = new AtomicInteger[THREADS];
        for (int i = 0; i < THREADS; i++) {
            acceptedPerAccount[i] = new AtomicInteger();
        }

        Outcome outcome = runForOneRound(threadIndex -> {
            HttpStatus status = deposit(clients.get(threadIndex), DEPOSIT_AMOUNT);
            if (status == HttpStatus.CREATED) {
                acceptedPerAccount[threadIndex].incrementAndGet();
            }
            return status;
        });

        log.info("Deposit load: {} requests, {} accepted, {} conflicted ({}%) — "
                        + "contention is the system account alone",
                outcome.total(), outcome.accepted().get(), outcome.conflicted().get(),
                String.format("%.2f", outcome.conflictRatePercent()));

        assertThat(outcome.unexpected().get())
                .as("every response should be a known outcome, never a 500")
                .isZero();

        assertLedgerSumsToZero();
        assertAccountsReconcile(clients);

        // Each account holds exactly what it was told it received: a conflict must
        // roll back completely, never half-apply.
        for (int i = 0; i < THREADS; i++) {
            BigDecimal expected = OPENING_BALANCE.add(
                    DEPOSIT_AMOUNT.multiply(new BigDecimal(acceptedPerAccount[i].get())));

            assertThat(accountRepository.findById(clients.get(i).accountId())
                    .orElseThrow().getBalance())
                    .as("account %d must hold exactly its accepted deposits", i)
                    .isEqualByComparingTo(expected);
        }
    }
}
