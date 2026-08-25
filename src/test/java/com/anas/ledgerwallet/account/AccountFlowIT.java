package com.anas.ledgerwallet.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.anas.ledgerwallet.IntegrationTestBase;
import com.anas.ledgerwallet.account.dto.AccountResponse;
import com.anas.ledgerwallet.auth.dto.AuthResponse;
import com.anas.ledgerwallet.auth.dto.LoginRequest;
import com.anas.ledgerwallet.auth.dto.RegisterRequest;
import com.anas.ledgerwallet.auth.dto.UserResponse;
import com.anas.ledgerwallet.common.error.ErrorResponse;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Account creation and read access end to end, including the authorisation boundary
 * between two real users.
 */
class AccountFlowIT extends IntegrationTestBase {

    private static final String PASSWORD = "a-sufficiently-long-password";

    @Autowired private TestRestTemplate restTemplate;

    /** Registers a fresh user and returns their bearer token. */
    private String newUserToken() {
        String email = "user-" + UUID.randomUUID() + "@example.com";

        ResponseEntity<UserResponse> registered = restTemplate.postForEntity(
                "/api/v1/auth/register",
                new RegisterRequest(email, PASSWORD, "Test User"),
                UserResponse.class);
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<AuthResponse> loggedIn = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, PASSWORD), AuthResponse.class);
        assertThat(loggedIn.getBody()).isNotNull();

        return loggedIn.getBody().accessToken();
    }

    private HttpEntity<Void> authorised(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(headers);
    }

    private ResponseEntity<AccountResponse> createAccount(String token) {
        return restTemplate.exchange(
                "/api/v1/accounts", HttpMethod.POST, authorised(token), AccountResponse.class);
    }

    private ResponseEntity<java.util.List<AccountResponse>> listAccounts(String token) {
        return restTemplate.exchange(
                "/api/v1/accounts",
                HttpMethod.GET,
                authorised(token),
                new ParameterizedTypeReference<>() {});
    }

    @Test
    @DisplayName("Create an account, list it, and fetch it by id")
    void createListAndFetch() {
        String token = newUserToken();

        ResponseEntity<AccountResponse> created = createAccount(token);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        assertThat(created.getBody().balance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(created.getBody().status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(created.getBody().accountNumber()).startsWith("ACC-");

        ResponseEntity<java.util.List<AccountResponse>> listed = listAccounts(token);
        assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listed.getBody()).isNotNull();
        assertThat(listed.getBody()).extracting(AccountResponse::id)
                .containsExactly(created.getBody().id());

        ResponseEntity<AccountResponse> fetched = restTemplate.exchange(
                "/api/v1/accounts/" + created.getBody().id(),
                HttpMethod.GET,
                authorised(token),
                AccountResponse.class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody()).isNotNull();
        assertThat(fetched.getBody().id()).isEqualTo(created.getBody().id());
    }

    @Test
    @DisplayName("One user may hold several accounts")
    void supportsMultipleAccountsPerUser() {
        String token = newUserToken();

        UUID first = createAccount(token).getBody().id();
        UUID second = createAccount(token).getBody().id();

        assertThat(first).isNotEqualTo(second);
        assertThat(listAccounts(token).getBody()).extracting(AccountResponse::id)
                .containsExactlyInAnyOrder(first, second);
    }

    @Test
    @DisplayName("Reading another user's account is refused with 403")
    void cannotReadAnotherUsersAccount() {
        String ownerToken = newUserToken();
        String intruderToken = newUserToken();

        UUID victimAccountId = createAccount(ownerToken).getBody().id();

        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                "/api/v1/accounts/" + victimAccountId,
                HttpMethod.GET,
                authorised(intruderToken),
                ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("FORBIDDEN");
        // The refusal must not describe what it is refusing.
        assertThat(response.getBody().message()).doesNotContain(victimAccountId.toString());
    }

    @Test
    @DisplayName("A user's listing never contains another user's accounts")
    void listingIsScopedToOwner() {
        String ownerToken = newUserToken();
        String otherToken = newUserToken();

        UUID ownersAccount = createAccount(ownerToken).getBody().id();
        createAccount(otherToken);

        assertThat(listAccounts(otherToken).getBody())
                .extracting(AccountResponse::id)
                .doesNotContain(ownersAccount);
    }

    @Test
    @DisplayName("An unknown account id returns 404")
    void unknownAccountReturnsNotFound() {
        String token = newUserToken();

        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                "/api/v1/accounts/" + UUID.randomUUID(),
                HttpMethod.GET,
                authorised(token),
                ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("ACCOUNT_NOT_FOUND");
    }

    @Test
    @DisplayName("All account endpoints require authentication")
    void accountEndpointsRequireAuthentication() {
        assertThat(restTemplate.getForEntity("/api/v1/accounts", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(restTemplate.postForEntity("/api/v1/accounts", null, String.class)
                .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(restTemplate.getForEntity(
                "/api/v1/accounts/" + UUID.randomUUID(), String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
