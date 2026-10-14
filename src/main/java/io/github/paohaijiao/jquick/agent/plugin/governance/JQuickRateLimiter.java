package io.github.paohaijiao.jquick.agent.plugin.governance;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token-bucket rate limiter.
 *
 * <p>A token bucket rather than a fixed window counter, because a fixed window lets a client burst at
 * twice the configured rate across a window boundary — the exact failure mode a limiter exists to
 * prevent. The bucket refills continuously at {@code permitsPerSecond} up to {@code burst}, and a
 * call is admitted only when a whole token is available.
 *
 * <p>Limits are per <b>key</b>, so the same limiter serves every method and every caller
 * independently. The key is chosen by the caller — a method signature for method level limiting, a
 * user id for per-user limiting.
 *
 * @author Martin
 */
public final class JQuickRateLimiter {

    private final double permitsPerSecond;

    private final double burst;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<String, Bucket>();

    public JQuickRateLimiter(double permitsPerSecond, int burst) {
        this.permitsPerSecond = permitsPerSecond <= 0d ? Double.MAX_VALUE : permitsPerSecond;
        this.burst = burst <= 0 ? 1d : burst;
    }

    public double getPermitsPerSecond() {
        return permitsPerSecond;
    }

    public int getBurst() {
        return (int) burst;
    }

    /**
     * Attempts to consume one permit.
     *
     * @return {@code true} when the call may proceed
     */
    public boolean tryAcquire(String key) {
        Bucket bucket = buckets.get(key);
        if (bucket == null) {
            Bucket created = new Bucket(burst);
            Bucket previous = buckets.putIfAbsent(key, created);
            bucket = previous != null ? previous : created;
        }
        return bucket.tryAcquire(permitsPerSecond, burst);
    }

    public void reset() {
        buckets.clear();
    }

    public int trackedKeys() {
        return buckets.size();
    }

    /**
     * One bucket; synchronised because the refill and the consumption must be atomic together.
     */
    private static final class Bucket {

        private double tokens;

        private long lastRefillNanos = System.nanoTime();

        Bucket(double initialTokens) {
            this.tokens = initialTokens;
        }

        synchronized boolean tryAcquire(double permitsPerSecond, double maxTokens) {
            long now = System.nanoTime();
            double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000d;
            lastRefillNanos = now;
            tokens = Math.min(maxTokens, tokens + elapsedSeconds * permitsPerSecond);
            if (tokens >= 1d) {
                tokens -= 1d;
                return true;
            }
            return false;
        }
    }
}
