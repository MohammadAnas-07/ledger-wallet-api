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
import java.util.UUID;
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
 * Idempotency keys against a real database, including the unique index that enforces
 * their scope.
 *
 * <p>The scoping is the security-relevant part: a key read on its own belongs to
 * whoever sends it first, which turns the replay path into a way to read a stranger's
 * transaction — and a way to make a stranger's request quietly do nothing.
 */
class IdempotencyIT extends IntegrationTestBase {

    private static final String PASSWORD = "a-sufficiently-long-password";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private AccountRepository accountRepository;

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

    private <T> ResponseEntity<T> deposit(
            String token, UUID accountId, String amount, String key, Class<T> type) {

        return restTemplate.exchange(
                "/api/v1/accounts/" + accountId + "/deposit",
                HttpMethod.POST,
                new HttpEntity<>(
                        new MoneyMovementRequest(new BigDecimal(amount), key), bearer(token)),
                type);
    }

    private BigDecimal balanceOf(UUID accountId) {
        return accountRepository.findById(accountId).orElseThrow().getBalance();
    }

    @Test
    @DisplayName("Two users may use the same key without seeing each other's transaction")
    void keysAreScopedToTheirOwner() {
        String key = "shared-key-" + UUID.randomUUID();

        String firstToken = newUserToken();
        UUID firstAccount = newAccount(firstToken);
        ResponseEntity<TransactionResponse> first =
                deposit(firstToken, firstAccount, "70.00", key, TransactionResponse.class);

        String secondToken = newUserToken();
        UUID secondAccount = newAccount(secondToken);
        ResponseEntity<TransactionResponse> second =
                deposit(secondToken, secondAccount, "25.00", key, TransactionResponse.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // The second caller gets their own transaction, for their own amount, with
        // their own balance — not a replay of the first user's deposit.
        assertThat(second.getBody().transactionId()).isNotEqualTo(first.getBody().transactionId());
        assertThat(second.getBody().amount()).isEqualByComparingTo("25.00");
        assertThat(second.getBody().balanceAfter()).isEqualByComparingTo("25.00");
        assertThat(second.getBody().accountId()).isEqualTo(secondAccount);

        // And the money actually moved on both sides, so neither request was swallowed.
        assertThat(balanceOf(firstAccount)).isEqualByComparingTo("70.00");
        assertThat(balanceOf(secondAccount)).isEqualByComparingTo("25.00");
    }

    @Test
    @DisplayName("Repeating the identical request replays it and moves money once")
    void identicalRequestReplays() {
        String token = newUserToken();
        UUID accountId = newAccount(token);
        String key = "replay-key-" + UUID.randomUUID();

        ResponseEntity<TransactionResponse> first =
                deposit(token, accountId, "40.00", key, TransactionResponse.class);
        ResponseEntity<TransactionResponse> repeated =
                deposit(token, accountId, "40.00", key, TransactionResponse.class);

        assertThat(repeated.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(repeated.getBody().transactionId()).isEqualTo(first.getBody().transactionId());
        assertThat(balanceOf(accountId)).isEqualByComparingTo("40.00");
    }

    @Test
    @DisplayName("Reusing an own key for a different amount is refused with 409")
    void reusedKeyForADifferentRequestIsRefused() {
        String token = newUserToken();
        UUID accountId = newAccount(token);
        String key = "reused-key-" + UUID.randomUUID();

        deposit(token, accountId, "40.00", key, TransactionResponse.class);
        ResponseEntity<ErrorResponse> reused =
                deposit(token, accountId, "500.00", key, ErrorResponse.class);

        assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(reused.getBody().code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        // Refused, so neither amount was applied a second time.
        assertThat(balanceOf(accountId)).isEqualByComparingTo("40.00");
    }
}
