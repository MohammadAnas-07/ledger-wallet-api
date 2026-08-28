package com.anas.ledgerwallet.common.ratelimit;

import com.anas.ledgerwallet.common.error.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Throttles the two unauthenticated endpoints, per client address.
 *
 * <p>These are the only paths reachable without a token, so they are the only ones an
 * attacker can hammer without first getting in. Login is the brute-force target;
 * register is the enumeration target, since a duplicate address answers 409 and that
 * answer is a yes/no oracle for "is this email registered".
 *
 * <p>Runs before {@code JwtAuthenticationFilter} and therefore before any BCrypt
 * comparison or database read. That ordering is the point: rejecting at the edge
 * costs nothing, while a limiter placed after authentication would still let every
 * attempt spend a strength-12 hash — which is itself a way to exhaust the CPU.
 *
 * <p>Keyed on {@code getRemoteAddr()} and deliberately not on
 * {@code X-Forwarded-For}: that header is caller-supplied, so trusting it here would
 * hand every attacker an unlimited supply of identities. Running behind a reverse
 * proxy would mean configuring {@code ForwardedHeaderFilter} with a trusted proxy
 * list, not reading the header directly.
 */
@Component
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final String LOGIN_PATH = "/api/v1/auth/login";
    private static final String REGISTER_PATH = "/api/v1/auth/register";

    private static final String ERROR_CODE = "RATE_LIMIT_EXCEEDED";
    /** Says nothing about which limit was hit or how much budget remains. */
    private static final String ERROR_MESSAGE = "Too many requests; please try again later";

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private static final Logger log = LoggerFactory.getLogger(AuthRateLimitFilter.class);

    private final Map<String, IpRateLimiter> limitersByPath;
    private final ObjectMapper objectMapper;

    // Explicit, because a second constructor exists for tests: with two candidates
    // and no annotation, Spring falls back to a no-arg constructor and fails.
    @Autowired
    public AuthRateLimitFilter(
            ObjectMapper objectMapper,
            @Value("${app.rate-limit.login.capacity}") int loginCapacity,
            @Value("${app.rate-limit.login.refill-period}") Duration loginRefillPeriod,
            @Value("${app.rate-limit.register.capacity}") int registerCapacity,
            @Value("${app.rate-limit.register.refill-period}") Duration registerRefillPeriod) {

        this(objectMapper,
                new IpRateLimiter(loginCapacity, loginRefillPeriod),
                new IpRateLimiter(registerCapacity, registerRefillPeriod));
    }

    /** Test seam: limiters built directly, without going through configuration. */
    AuthRateLimitFilter(
            ObjectMapper objectMapper, IpRateLimiter loginLimiter, IpRateLimiter registerLimiter) {

        this.objectMapper = objectMapper;
        // Separate budgets: exhausting the login allowance must not also lock a
        // legitimate visitor out of registering, and the two attacks differ in shape.
        this.limitersByPath = Map.of(
                LOGIN_PATH, loginLimiter,
                REGISTER_PATH, registerLimiter);
    }

    /**
     * Everything except the two public auth paths passes straight through.
     *
     * <p>Exact match on the request URI is enough because {@code StrictHttpFirewall}
     * rejects the encoded, doubled-slash and traversal forms of a path before any
     * filter runs, and Spring MVC matches the remaining ones case-sensitively with no
     * trailing-slash tolerance. A path that would reach the controller therefore
     * reaches this map too.
     */
    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !limitersByPath.containsKey(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        IpRateLimiter limiter = limitersByPath.get(request.getRequestURI());
        ConsumptionProbe probe = limiter.tryConsume(request.getRemoteAddr());

        if (probe.isConsumed()) {
            filterChain.doFilter(request, response);
            return;
        }

        rejectAsTooManyRequests(request, response, probe);
    }

    private void rejectAsTooManyRequests(
            HttpServletRequest request, HttpServletResponse response, ConsumptionProbe probe)
            throws IOException {

        // The address is logged, the payload is not: a rejected login attempt still
        // carries a password (rules.md 4.5).
        log.warn("Rate limit exceeded for {} on {}",
                request.getRemoteAddr(), request.getRequestURI());

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(
                HttpHeaders.RETRY_AFTER, String.valueOf(secondsUntilRefill(probe)));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        // The same error shape every other failure uses (architecture.md 7). A filter
        // sits outside @RestControllerAdvice, so the body is written here rather than
        // translated later.
        objectMapper.writeValue(
                response.getOutputStream(),
                ErrorResponse.of(ERROR_CODE, ERROR_MESSAGE, request.getRequestURI()));
    }

    /** Rounded up, and never zero: {@code Retry-After: 0} invites an instant retry. */
    private long secondsUntilRefill(ConsumptionProbe probe) {
        long seconds = (probe.getNanosToWaitForRefill() + NANOS_PER_SECOND - 1) / NANOS_PER_SECOND;
        return Math.max(seconds, 1);
    }
}
