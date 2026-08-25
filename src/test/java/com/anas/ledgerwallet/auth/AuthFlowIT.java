package com.anas.ledgerwallet.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.anas.ledgerwallet.IntegrationTestBase;
import com.anas.ledgerwallet.auth.dto.AuthResponse;
import com.anas.ledgerwallet.auth.dto.LoginRequest;
import com.anas.ledgerwallet.auth.dto.RegisterRequest;
import com.anas.ledgerwallet.auth.dto.UserResponse;
import com.anas.ledgerwallet.common.error.ErrorResponse;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
 * The end-to-end authentication journey against a real database: register, log in,
 * and reach a protected endpoint with the issued token.
 */
class AuthFlowIT extends IntegrationTestBase {

    private static final String PASSWORD = "a-sufficiently-long-password";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;

    private String email;

    @BeforeEach
    void setUp() {
        // Unique per test: the users table persists across tests in the shared
        // container, so a fixed address would collide on the second run.
        email = "user-" + UUID.randomUUID() + "@example.com";
    }

    private ResponseEntity<UserResponse> register(String address) {
        return restTemplate.postForEntity(
                "/api/v1/auth/register",
                new RegisterRequest(address, PASSWORD, "Test User"),
                UserResponse.class);
    }

    private ResponseEntity<AuthResponse> login(String address, String password) {
        return restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(address, password), AuthResponse.class);
    }

    private ResponseEntity<UserResponse> getMe(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(
                "/api/v1/auth/me", HttpMethod.GET, new HttpEntity<>(headers), UserResponse.class);
    }

    @Test
    @DisplayName("Register, log in, then reach a protected endpoint with the token")
    void fullJourney() {
        ResponseEntity<UserResponse> registered = register(email);
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(registered.getBody()).isNotNull();
        assertThat(registered.getBody().email()).isEqualTo(email);

        ResponseEntity<AuthResponse> loggedIn = login(email, PASSWORD);
        assertThat(loggedIn.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loggedIn.getBody()).isNotNull();
        assertThat(loggedIn.getBody().accessToken()).isNotBlank();
        assertThat(loggedIn.getBody().tokenType()).isEqualTo("Bearer");

        ResponseEntity<UserResponse> me = getMe(loggedIn.getBody().accessToken());
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody()).isNotNull();
        assertThat(me.getBody().id()).isEqualTo(registered.getBody().id());
        assertThat(me.getBody().email()).isEqualTo(email);
    }

    @Test
    @DisplayName("The stored password is a hash, and no response echoes it back")
    void passwordIsHashedAndNeverReturned() {
        register(email);

        User stored = userRepository.findByEmail(email).orElseThrow();
        assertThat(stored.getPasswordHash()).isNotEqualTo(PASSWORD);
        assertThat(stored.getPasswordHash()).startsWith("$2");

        ResponseEntity<String> raw = restTemplate.postForEntity(
                "/api/v1/auth/register",
                new RegisterRequest(
                        "other-" + UUID.randomUUID() + "@example.com", PASSWORD, "Test User"),
                String.class);
        assertThat(raw.getBody()).doesNotContain(PASSWORD).doesNotContain("passwordHash");
    }

    @Test
    @DisplayName("The protected endpoint rejects a request with no token")
    void protectedEndpointRequiresToken() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("/api/v1/auth/me", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("The protected endpoint rejects a token signed by someone else")
    void protectedEndpointRejectsForgedToken() {
        // A structurally valid JWT signed with an attacker's own key.
        String forged = "eyJhbGciOiJIUzI1NiJ9"
                + ".eyJzdWIiOiIwMDAwMDAwMC0wMDAwLTAwMDAtMDAwMC0wMDAwMDAwMDAwMDAifQ"
                + ".Ck1nZ0hDX2ZvcmdlZF9zaWduYXR1cmVfbm90X3ZhbGlk";

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/auth/me",
                HttpMethod.GET,
                new HttpEntity<>(bearer(forged)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Registering the same email twice returns 409")
    void duplicateEmailIsRejected() {
        assertThat(register(email).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<ErrorResponse> second = restTemplate.postForEntity(
                "/api/v1/auth/register",
                new RegisterRequest(email, PASSWORD, "Test User"),
                ErrorResponse.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).isNotNull();
        assertThat(second.getBody().code()).isEqualTo("EMAIL_ALREADY_REGISTERED");
    }

    @Test
    @DisplayName("Email is treated case-insensitively for both registration and login")
    void emailIsCaseInsensitive() {
        register(email);

        ResponseEntity<ErrorResponse> duplicate = restTemplate.postForEntity(
                "/api/v1/auth/register",
                new RegisterRequest(email.toUpperCase(), PASSWORD, "Test User"),
                ErrorResponse.class);
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        assertThat(login(email.toUpperCase(), PASSWORD).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("A wrong password and an unknown email fail identically")
    void credentialFailuresAreIndistinguishable() {
        register(email);

        ResponseEntity<ErrorResponse> wrongPassword = restTemplate.postForEntity(
                "/api/v1/auth/login",
                new LoginRequest(email, "the-wrong-password-entirely"),
                ErrorResponse.class);

        ResponseEntity<ErrorResponse> unknownEmail = restTemplate.postForEntity(
                "/api/v1/auth/login",
                new LoginRequest("nobody-" + UUID.randomUUID() + "@example.com", PASSWORD),
                ErrorResponse.class);

        // Any difference between these two — status, code, or wording — tells an
        // attacker which emails are registered (rules.md 2.2).
        assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknownEmail.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrongPassword.getBody()).isNotNull();
        assertThat(unknownEmail.getBody()).isNotNull();
        assertThat(wrongPassword.getBody().code()).isEqualTo(unknownEmail.getBody().code());
        assertThat(wrongPassword.getBody().message()).isEqualTo(unknownEmail.getBody().message());
    }

    @Test
    @DisplayName("Invalid registration input is rejected with 400 and nothing is stored")
    void validationRejectsBadInput() {
        ResponseEntity<ErrorResponse> shortPassword = restTemplate.postForEntity(
                "/api/v1/auth/register",
                new RegisterRequest(email, "short", "Test User"),
                ErrorResponse.class);

        assertThat(shortPassword.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(shortPassword.getBody()).isNotNull();
        assertThat(shortPassword.getBody().code()).isEqualTo("VALIDATION_ERROR");
        assertThat(userRepository.findByEmail(email)).isEmpty();

        ResponseEntity<ErrorResponse> badEmail = restTemplate.postForEntity(
                "/api/v1/auth/register",
                new RegisterRequest("not-an-email", PASSWORD, "Test User"),
                ErrorResponse.class);
        assertThat(badEmail.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
