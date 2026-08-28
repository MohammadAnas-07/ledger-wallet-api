package com.anas.ledgerwallet.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The rate limiter's own behaviour, with no Spring context.
 *
 * <p>Wiring into the security chain is a separate question and is covered by
 * {@code AuthRateLimitIT} — a filter that works perfectly but was never registered
 * would pass every test here.
 */
class AuthRateLimitFilterTest {

    private static final String LOGIN_PATH = "/api/v1/auth/login";
    private static final String REGISTER_PATH = "/api/v1/auth/register";
    private static final int CAPACITY = 3;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private AuthRateLimitFilter filterAllowing(int capacity) {
        return new AuthRateLimitFilter(
                objectMapper,
                new IpRateLimiter(capacity, Duration.ofMinutes(1)),
                new IpRateLimiter(capacity, Duration.ofMinutes(1)));
    }

    /** Counts pass-throughs; {@code MockFilterChain} refuses to be called twice. */
    private static final class CountingFilterChain implements FilterChain {
        private int passedThrough;

        @Override
        public void doFilter(ServletRequest request, ServletResponse response)
                throws IOException, ServletException {
            passedThrough++;
        }
    }

    private MockHttpServletResponse send(
            AuthRateLimitFilter filter, String path, String clientIp, CountingFilterChain chain)
            throws ServletException, IOException {

        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr(clientIp);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);
        return response;
    }

    @Test
    @DisplayName("Requests within the allowance reach the rest of the chain")
    void allowsRequestsWithinAllowance() throws Exception {
        AuthRateLimitFilter filter = filterAllowing(CAPACITY);
        CountingFilterChain chain = new CountingFilterChain();

        for (int i = 0; i < CAPACITY; i++) {
            MockHttpServletResponse response = send(filter, LOGIN_PATH, "10.0.0.1", chain);
            assertThat(response.getStatus()).isEqualTo(200);
        }

        assertThat(chain.passedThrough).isEqualTo(CAPACITY);
    }

    @Test
    @DisplayName("The request past the allowance is refused with 429 and never reaches the chain")
    void refusesRequestPastAllowance() throws Exception {
        AuthRateLimitFilter filter = filterAllowing(CAPACITY);
        CountingFilterChain chain = new CountingFilterChain();

        for (int i = 0; i < CAPACITY; i++) {
            send(filter, LOGIN_PATH, "10.0.0.1", chain);
        }
        MockHttpServletResponse refused = send(filter, LOGIN_PATH, "10.0.0.1", chain);

        assertThat(refused.getStatus()).isEqualTo(429);
        // The whole point: the throttled attempt costs no BCrypt comparison.
        assertThat(chain.passedThrough).isEqualTo(CAPACITY);
    }

    @Test
    @DisplayName("A refusal carries Retry-After and the standard error body")
    void refusalCarriesRetryAfterAndErrorBody() throws Exception {
        AuthRateLimitFilter filter = filterAllowing(1);
        CountingFilterChain chain = new CountingFilterChain();

        send(filter, LOGIN_PATH, "10.0.0.1", chain);
        MockHttpServletResponse refused = send(filter, LOGIN_PATH, "10.0.0.1", chain);

        assertThat(refused.getHeader("Retry-After")).isNotNull();
        assertThat(Long.parseLong(refused.getHeader("Retry-After"))).isPositive();
        assertThat(refused.getContentType()).startsWith("application/json");

        ObjectNode body = (ObjectNode) objectMapper.readTree(refused.getContentAsString());
        assertThat(body.get("code").asText()).isEqualTo("RATE_LIMIT_EXCEEDED");
        assertThat(body.get("path").asText()).isEqualTo(LOGIN_PATH);
        // Says nothing about remaining budget, the configured limit, or the account.
        assertThat(body.get("message").asText()).isEqualTo(
                "Too many requests; please try again later");
    }

    @Test
    @DisplayName("One client exhausting its allowance does not affect another address")
    void budgetsArePerClientAddress() throws Exception {
        AuthRateLimitFilter filter = filterAllowing(1);
        CountingFilterChain chain = new CountingFilterChain();

        send(filter, LOGIN_PATH, "10.0.0.1", chain);
        assertThat(send(filter, LOGIN_PATH, "10.0.0.1", chain).getStatus()).isEqualTo(429);

        assertThat(send(filter, LOGIN_PATH, "10.0.0.2", chain).getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("Login and register hold separate budgets")
    void budgetsArePerEndpoint() throws Exception {
        AuthRateLimitFilter filter = filterAllowing(1);
        CountingFilterChain chain = new CountingFilterChain();

        send(filter, LOGIN_PATH, "10.0.0.1", chain);
        assertThat(send(filter, LOGIN_PATH, "10.0.0.1", chain).getStatus()).isEqualTo(429);

        // Being locked out of login must not lock a real visitor out of registering.
        assertThat(send(filter, REGISTER_PATH, "10.0.0.1", chain).getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("Endpoints other than the two public auth paths are not throttled")
    void otherPathsAreNotThrottled() throws Exception {
        AuthRateLimitFilter filter = filterAllowing(1);
        CountingFilterChain chain = new CountingFilterChain();

        for (int i = 0; i < 10; i++) {
            MockHttpServletResponse response =
                    send(filter, "/api/v1/transfers", "10.0.0.1", chain);
            assertThat(response.getStatus()).isEqualTo(200);
        }

        assertThat(chain.passedThrough).isEqualTo(10);
    }
}
