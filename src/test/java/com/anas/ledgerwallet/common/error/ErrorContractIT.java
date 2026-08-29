package com.anas.ledgerwallet.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.anas.ledgerwallet.IntegrationTestBase;
import com.anas.ledgerwallet.auth.dto.AuthResponse;
import com.anas.ledgerwallet.auth.dto.LoginRequest;
import com.anas.ledgerwallet.auth.dto.RegisterRequest;
import com.anas.ledgerwallet.auth.dto.UserResponse;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * What a badly formed request gets back.
 *
 * <p>Every case here used to fall through to the catch-all handler and return 500
 * with a stack trace logged at ERROR — a caller's typo reported as a server fault,
 * and a free way to fill the logs. They are client errors and must say so.
 */
class ErrorContractIT extends IntegrationTestBase {

    private static final String PASSWORD = "a-sufficiently-long-password";

    @Autowired private TestRestTemplate restTemplate;

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

    private ResponseEntity<ErrorResponse> get(String path, String token) {
        return restTemplate.exchange(
                path, HttpMethod.GET, new HttpEntity<>(bearer(token)), ErrorResponse.class);
    }

    @Test
    @DisplayName("A path variable that is not a UUID is a 400, not a 500")
    void malformedPathVariableIsBadRequest() {
        ResponseEntity<ErrorResponse> response =
                get("/api/v1/accounts/not-a-uuid", newUserToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("VALIDATION_ERROR");
        // Names the parameter and the type it wanted, and nothing internal.
        assertThat(response.getBody().message()).contains("id").contains("UUID");
    }

    @Test
    @DisplayName("A non-numeric query parameter is a 400, not a 500")
    void malformedQueryParameterIsBadRequest() {
        String token = newUserToken();

        ResponseEntity<ErrorResponse> response =
                get("/api/v1/accounts/" + UUID.randomUUID() + "/transactions?page=abc", token);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    @DisplayName("A body that cannot be parsed is a 400 that names no parser internals")
    void malformedBodyIsBadRequest() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                "/api/v1/auth/login",
                HttpMethod.POST,
                new HttpEntity<>("{\"email\": ", headers),
                ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("VALIDATION_ERROR");
        assertThat(response.getBody().message()).isEqualTo("Request body is missing or malformed");
    }

    @Test
    @DisplayName("The wrong method on a real path is a 405")
    void wrongMethodIsMethodNotAllowed() {
        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                "/api/v1/auth/login",
                HttpMethod.GET,
                new HttpEntity<>(bearer(newUserToken())),
                ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody().code()).isEqualTo("METHOD_NOT_ALLOWED");
    }

    @Test
    @DisplayName("An authenticated caller reaching a path that does not exist gets a 404")
    void unknownPathIsNotFound() {
        // Anonymous callers are refused by the filter chain first, so this case only
        // exists once authenticated.
        ResponseEntity<ErrorResponse> response = get("/api/v1/no-such-endpoint", newUserToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("No error body carries a stack trace or an internal class name")
    void errorsCarryNoInternals() {
        ResponseEntity<ErrorResponse> response =
                get("/api/v1/accounts/not-a-uuid", newUserToken());

        // rules.md 4.5: no stack traces, SQL, or internal class names in a response.
        assertThat(response.getBody().message())
                .doesNotContain("Exception")
                .doesNotContain("com.anas")
                .doesNotContain("org.springframework");
    }
}
