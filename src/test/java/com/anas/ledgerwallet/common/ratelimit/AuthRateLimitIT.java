package com.anas.ledgerwallet.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.anas.ledgerwallet.TestInfrastructure;
import com.anas.ledgerwallet.auth.dto.LoginRequest;
import com.anas.ledgerwallet.auth.dto.RegisterRequest;
import com.anas.ledgerwallet.common.error.ErrorResponse;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Proves the limiter is actually wired into the security filter chain.
 *
 * <p>{@code AuthRateLimitFilterTest} already covers the counting. What it cannot
 * cover is registration: a filter that is never added to the chain passes every unit
 * test and throttles nothing in production.
 *
 * <p>Runs in its own application context with deliberately tiny allowances, which is
 * why it does not extend {@code IntegrationTestBase} — that class lifts the limits so
 * the rest of the suite is not throttled, and its {@code @DynamicPropertySource}
 * would win over anything set here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthRateLimitIT {

    private static final int LOGIN_CAPACITY = 3;
    private static final int REGISTER_CAPACITY = 2;

    private static final String PASSWORD = "a-sufficiently-long-password";

    @DynamicPropertySource
    static void applicationProperties(DynamicPropertyRegistry registry) {
        TestInfrastructure.register(registry);

        registry.add("app.rate-limit.login.capacity", () -> LOGIN_CAPACITY);
        registry.add("app.rate-limit.register.capacity", () -> REGISTER_CAPACITY);
        // Long enough that nothing refills mid-test: a bucket topping up between
        // assertions is how a limiter test becomes intermittent.
        registry.add("app.rate-limit.login.refill-period", () -> "30m");
        registry.add("app.rate-limit.register.refill-period", () -> "30m");
    }

    @Autowired private TestRestTemplate restTemplate;

    private static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    private ResponseEntity<ErrorResponse> login(String email) {
        return restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, PASSWORD), ErrorResponse.class);
    }

    private ResponseEntity<ErrorResponse> register(String email) {
        return restTemplate.postForEntity(
                "/api/v1/auth/register",
                new RegisterRequest(email, PASSWORD, "Test User"),
                ErrorResponse.class);
    }

    @Test
    @DisplayName("Login refuses with 429 once the allowance is spent, not with another 401")
    void loginIsRateLimited() {
        for (int attempt = 1; attempt <= LOGIN_CAPACITY; attempt++) {
            // Unknown address: every one of these is a failed credential check, which
            // is exactly the shape of a brute-force attempt.
            assertThat(login(uniqueEmail()).getStatusCode())
                    .as("attempt %d should still be allowed through", attempt)
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        ResponseEntity<ErrorResponse> throttled = login(uniqueEmail());

        assertThat(throttled.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(throttled.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotNull();
        assertThat(throttled.getBody()).isNotNull();
        assertThat(throttled.getBody().code()).isEqualTo("RATE_LIMIT_EXCEEDED");
    }

    @Test
    @DisplayName("Register is throttled on its own budget")
    void registerIsRateLimited() {
        for (int attempt = 1; attempt <= REGISTER_CAPACITY; attempt++) {
            assertThat(register(uniqueEmail()).getStatusCode())
                    .as("attempt %d should still be allowed through", attempt)
                    .isEqualTo(HttpStatus.CREATED);
        }

        // The budget is what stops an attacker walking a list of addresses through
        // the 409 that a duplicate registration returns.
        assertThat(register(uniqueEmail()).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("Protected endpoints are not throttled by the auth limiter")
    void protectedEndpointsAreNotThrottled() {
        // Well past both allowances: an unauthenticated caller must keep getting 401
        // here, not 429. The limiter defends the two public paths, and turning it into
        // a general throttle would change what every other endpoint returns.
        for (int attempt = 1; attempt <= LOGIN_CAPACITY + REGISTER_CAPACITY + 5; attempt++) {
            ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                    "/api/v1/auth/me", HttpMethod.GET, null, ErrorResponse.class);

            assertThat(response.getStatusCode())
                    .as("attempt %d", attempt)
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }
}
