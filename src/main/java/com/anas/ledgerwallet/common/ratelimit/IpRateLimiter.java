package com.anas.ledgerwallet.common.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * One token bucket per client, holding a single rate limit.
 *
 * <p>Buckets live in memory in this instance only. That is honest about what it
 * defends: a brute-force attempt against one running application. Behind several
 * replicas each would enforce the limit separately, so the effective ceiling is the
 * limit times the replica count — closing that needs a shared backend (bucket4j has
 * Redis and Hazelcast modules), which this deployment does not have.
 *
 * <p>Refill is greedy rather than interval-based: tokens trickle back continuously
 * instead of the whole allowance reappearing on a window boundary. A fixed window
 * would let an attacker send the full budget at the end of one window and again at
 * the start of the next, doubling the burst at exactly the wrong moment.
 */
final class IpRateLimiter {

    /**
     * Above this many tracked clients, idle entries are swept before the next
     * consumption. High enough that ordinary traffic never pays for a sweep, low
     * enough that a spray of one-request-per-address cannot grow the map without
     * bound.
     */
    private static final int DEFAULT_SWEEP_THRESHOLD = 10_000;

    private final Bandwidth limit;
    private final long idleNanos;
    private final LongSupplier clock;
    private final int sweepThreshold;

    private final ConcurrentHashMap<String, Entry> buckets = new ConcurrentHashMap<>();
    private final AtomicBoolean sweeping = new AtomicBoolean();

    IpRateLimiter(int capacity, Duration refillPeriod) {
        this(capacity, refillPeriod, System::nanoTime, DEFAULT_SWEEP_THRESHOLD);
    }

    /** Test seam: a controllable clock and a threshold small enough to reach. */
    IpRateLimiter(int capacity, Duration refillPeriod, LongSupplier clock, int sweepThreshold) {
        if (capacity < 1) {
            throw new IllegalArgumentException("Rate limit capacity must be at least 1");
        }
        if (refillPeriod.isZero() || refillPeriod.isNegative()) {
            throw new IllegalArgumentException("Rate limit refill period must be positive");
        }
        this.limit = Bandwidth.builder()
                .capacity(capacity)
                .refillGreedy(capacity, refillPeriod)
                .build();
        this.idleNanos = refillPeriod.toNanos();
        this.clock = clock;
        this.sweepThreshold = sweepThreshold;
    }

    /**
     * Takes one token for the given client.
     *
     * <p>The probe reports both the outcome and how long the caller must wait, which
     * is what the {@code Retry-After} header needs.
     */
    ConsumptionProbe tryConsume(String clientId) {
        long now = clock.getAsLong();

        Entry entry = buckets.computeIfAbsent(
                clientId, key -> new Entry(Bucket.builder().addLimit(limit).build(),
                        new AtomicLong(now)));
        entry.lastSeenNanos().set(now);

        sweepIfCrowded(now);

        return entry.bucket().tryConsumeAndReturnRemaining(1);
    }

    /**
     * Drops clients that have been quiet for longer than a full refill period.
     *
     * <p>Safe to do at any time: a bucket idle that long has refilled to capacity, so
     * forgetting it and creating a fresh one on the next request are the same thing.
     * One thread sweeps at a time; the rest carry on rather than queue behind it.
     */
    private void sweepIfCrowded(long now) {
        if (buckets.size() < sweepThreshold || !sweeping.compareAndSet(false, true)) {
            return;
        }
        try {
            // Subtraction, not comparison: nanoTime is allowed to be negative and to
            // wrap, and only the difference between two readings is meaningful.
            buckets.entrySet().removeIf(
                    entry -> now - entry.getValue().lastSeenNanos().get() > idleNanos);
        } finally {
            sweeping.set(false);
        }
    }

    /** Visible for tests: how many clients are currently tracked. */
    int trackedClients() {
        return buckets.size();
    }

    private record Entry(Bucket bucket, AtomicLong lastSeenNanos) {
    }
}
