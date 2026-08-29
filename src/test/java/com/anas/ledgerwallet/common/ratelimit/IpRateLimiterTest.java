package com.anas.ledgerwallet.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.bucket4j.TimeMeter;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The bucket store underneath the filter: refill, isolation, and eviction. */
class IpRateLimiterTest {

    private static final Duration PERIOD = Duration.ofMinutes(1);

    /** The settings the application actually runs with, so the arithmetic is the real one. */
    private static final int PRODUCTION_LOGIN_CAPACITY = 10;
    private static final Duration PRODUCTION_LOGIN_PERIOD = Duration.ofMinutes(1);

    /**
     * A clock the test moves by hand.
     *
     * <p>It drives bucket4j's own time source rather than the test sleeping: refill is
     * the behaviour under test, and waiting six real seconds for one token would be
     * both slow and, on a loaded machine, intermittent.
     */
    private static final class FakeClock implements TimeMeter {
        private long nanos;

        void advance(Duration amount) {
            nanos += amount.toNanos();
        }

        @Override
        public long currentTimeNanos() {
            return nanos;
        }

        @Override
        public boolean isWallClockBased() {
            return false;
        }
    }

    private static IpRateLimiter productionLimiter(FakeClock clock) {
        return new IpRateLimiter(
                PRODUCTION_LOGIN_CAPACITY, PRODUCTION_LOGIN_PERIOD, clock, Integer.MAX_VALUE);
    }

    private static int allowedOutOf(IpRateLimiter limiter, String client, int attempts) {
        int allowed = 0;
        for (int i = 0; i < attempts; i++) {
            if (limiter.tryConsume(client).isConsumed()) {
                allowed++;
            }
        }
        return allowed;
    }

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
        FakeClock clock = new FakeClock();
        // Threshold of 3, so the fourth distinct client triggers the sweep.
        IpRateLimiter limiter = new IpRateLimiter(1, PERIOD, clock, 3);

        limiter.tryConsume("idle-1");
        limiter.tryConsume("idle-2");
        limiter.tryConsume("idle-3");
        assertThat(limiter.trackedClients()).isEqualTo(3);

        // Long enough that every tracked bucket has refilled to capacity, which is
        // what makes dropping them equivalent to keeping them.
        clock.advance(PERIOD.multipliedBy(2));
        limiter.tryConsume("active");

        assertThat(limiter.trackedClients()).isEqualTo(1);
    }

    @Test
    @DisplayName("A client still inside its window survives the sweep with its budget spent")
    void keepsActiveClientsDuringSweep() {
        FakeClock clock = new FakeClock();
        IpRateLimiter limiter = new IpRateLimiter(1, PERIOD, clock, 3);

        limiter.tryConsume("attacker");
        limiter.tryConsume("idle-1");
        limiter.tryConsume("idle-2");

        // Far enough that the other two count as idle, but not so far that the
        // attacker's own bucket has refilled: the sweep must not forgive them.
        clock.advance(PERIOD.dividedBy(5));
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

    @Test
    @DisplayName("At the configured 10 per minute, one token returns every six seconds")
    void refillsOneTokenEverySixSecondsAtProductionSettings() {
        FakeClock clock = new FakeClock();
        IpRateLimiter limiter = productionLimiter(clock);

        assertThat(allowedOutOf(limiter, "10.0.0.1", PRODUCTION_LOGIN_CAPACITY))
                .isEqualTo(PRODUCTION_LOGIN_CAPACITY);
        assertThat(limiter.tryConsume("10.0.0.1").isConsumed()).isFalse();

        // Refill is greedy: tokens trickle back at capacity/period rather than the
        // whole allowance reappearing on a window boundary. A minute over ten is one
        // token every six seconds.
        clock.advance(Duration.ofSeconds(5));
        assertThat(limiter.tryConsume("10.0.0.1").isConsumed())
                .as("five seconds is not yet a token")
                .isFalse();

        clock.advance(Duration.ofSeconds(1));
        assertThat(limiter.tryConsume("10.0.0.1").isConsumed())
                .as("at six seconds exactly one token is back")
                .isTrue();
        assertThat(limiter.tryConsume("10.0.0.1").isConsumed())
                .as("and only one")
                .isFalse();
    }

    @Test
    @DisplayName("A long quiet period restores the allowance and never more than it")
    void refillsToCapacityAndStopsThere() {
        FakeClock clock = new FakeClock();
        IpRateLimiter limiter = productionLimiter(clock);

        allowedOutOf(limiter, "10.0.0.1", PRODUCTION_LOGIN_CAPACITY);

        // Ten times the refill period. The bucket must not bank tokens beyond its
        // capacity, or a patient attacker could save up an enormous burst.
        clock.advance(PRODUCTION_LOGIN_PERIOD.multipliedBy(10));

        assertThat(allowedOutOf(limiter, "10.0.0.1", 50)).isEqualTo(PRODUCTION_LOGIN_CAPACITY);
    }

    @Test
    @DisplayName("A sequential caller one second apart is throttled only on the twelfth attempt")
    void sequentialCallerIsThrottledLateRatherThanAtTen() {
        FakeClock clock = new FakeClock();
        IpRateLimiter limiter = productionLimiter(clock);

        // Tokens available before attempt i are 10 - i + i/6: the allowance minus what
        // has been spent, plus what has trickled back. At one second per request that
        // stays above 1 until the twelfth attempt, which finds 0.83 of a token.
        boolean[] allowed = new boolean[12];
        for (int attempt = 0; attempt < 12; attempt++) {
            allowed[attempt] = limiter.tryConsume("10.0.0.1").isConsumed();
            clock.advance(Duration.ofSeconds(1));
        }

        assertThat(allowed[10]).as("the eleventh attempt still finds a refilled token").isTrue();
        assertThat(allowed[11]).as("the twelfth is refused").isFalse();
    }

    @Test
    @DisplayName("A slower sequential caller is never throttled at all")
    void slowEnoughSequentialCallerIsNeverThrottled() {
        FakeClock clock = new FakeClock();
        IpRateLimiter limiter = productionLimiter(clock);

        // At a second and a half per request the bucket hands tokens back as fast as
        // this caller spends them, so twelve attempts all succeed. This is a sustained
        // rate limit, not a hard cap of ten per calendar minute.
        //
        // Written down because a manual check ran into exactly this: twelve sequential
        // logins against a freshly started server returned twelve 401s and no 429,
        // which looks like a limiter that is not wired in and is really a limiter
        // refilling as fast as it is drained. Anything above roughly 1.1 seconds per
        // request has that effect; a burst is what shows the limit.
        int allowed = 0;
        for (int attempt = 0; attempt < 12; attempt++) {
            if (limiter.tryConsume("10.0.0.1").isConsumed()) {
                allowed++;
            }
            clock.advance(Duration.ofMillis(1500));
        }

        assertThat(allowed).isEqualTo(12);
    }

    @Test
    @DisplayName("A burst outruns the refill and is throttled")
    void burstIsThrottled() {
        FakeClock clock = new FakeClock();
        IpRateLimiter limiter = productionLimiter(clock);

        // The same twenty requests with no gap: nothing refills, so everything past
        // the allowance is refused. This is the shape a brute-force attempt has, and
        // the shape a manual check has to use to see a 429.
        assertThat(allowedOutOf(limiter, "10.0.0.1", 20))
                .isEqualTo(PRODUCTION_LOGIN_CAPACITY);
    }
}
