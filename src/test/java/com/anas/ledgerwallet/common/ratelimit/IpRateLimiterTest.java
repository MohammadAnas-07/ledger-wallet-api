package com.anas.ledgerwallet.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The bucket store underneath the filter: refill, isolation, and eviction. */
class IpRateLimiterTest {

    private static final Duration PERIOD = Duration.ofMinutes(1);

    @Test
    @DisplayName("Tokens are spent down to the capacity, then refused")
    void spendsDownToCapacity() {
        IpRateLimiter limiter = new IpRateLimiter(2, PERIOD);

        assertThat(limiter.tryConsume("a").isConsumed()).isTrue();
        assertThat(limiter.tryConsume("a").isConsumed()).isTrue();
        assertThat(limiter.tryConsume("a").isConsumed()).isFalse();
    }

    @Test
    @DisplayName("A refusal reports how long to wait")
    void refusalReportsWaitTime() {
        IpRateLimiter limiter = new IpRateLimiter(1, PERIOD);
        limiter.tryConsume("a");

        assertThat(limiter.tryConsume("a").getNanosToWaitForRefill()).isPositive();
    }

    @Test
    @DisplayName("Each client has its own bucket")
    void clientsAreIsolated() {
        IpRateLimiter limiter = new IpRateLimiter(1, PERIOD);
        limiter.tryConsume("a");

        assertThat(limiter.tryConsume("b").isConsumed()).isTrue();
    }

    @Test
    @DisplayName("Clients idle for longer than a refill period are forgotten once the map grows")
    void evictsIdleClientsOnceCrowded() {
        AtomicLong now = new AtomicLong();
        // Threshold of 3, so the fourth distinct client triggers the sweep.
        IpRateLimiter limiter = new IpRateLimiter(1, PERIOD, now::get, 3);

        limiter.tryConsume("idle-1");
        limiter.tryConsume("idle-2");
        limiter.tryConsume("idle-3");
        assertThat(limiter.trackedClients()).isEqualTo(3);

        // Long enough that every tracked bucket has refilled to capacity, which is
        // what makes dropping them equivalent to keeping them.
        now.addAndGet(PERIOD.toNanos() * 2);
        limiter.tryConsume("active");

        assertThat(limiter.trackedClients()).isEqualTo(1);
    }

    @Test
    @DisplayName("A client still inside its window survives the sweep with its budget spent")
    void keepsActiveClientsDuringSweep() {
        AtomicLong now = new AtomicLong();
        IpRateLimiter limiter = new IpRateLimiter(1, PERIOD, now::get, 3);

        limiter.tryConsume("attacker");
        limiter.tryConsume("idle-1");
        limiter.tryConsume("idle-2");

        now.addAndGet(PERIOD.toNanos() * 2);
        // Keeps the attacker's entry fresh, so the sweep must not forgive it.
        assertThat(limiter.tryConsume("attacker").isConsumed()).isFalse();
    }

    @Test
    @DisplayName("A nonsensical limit fails fast rather than throttling nothing")
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new IpRateLimiter(0, PERIOD))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IpRateLimiter(1, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
