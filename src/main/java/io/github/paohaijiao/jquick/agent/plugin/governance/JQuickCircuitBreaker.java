package io.github.paohaijiao.jquick.agent.plugin.governance;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Failure-rate circuit breaker.
 *
 * <p>The classic three-state machine, applied per key (one per method, or one per downstream
 * dependency):
 *
 * <pre>
 *   CLOSED  --failure rate over the window exceeds the threshold-->  OPEN
 *   OPEN    --the cool-down elapses-->                              HALF_OPEN
 *   HALF_OPEN --one probe succeeds-->                               CLOSED
 *   HALF_OPEN --one probe fails-->                                  OPEN
 * </pre>
 *
 * <p>Only a <em>rate</em> over a <em>minimum number of samples</em> trips the breaker: tripping on a
 * single failure turns a transient blip into an outage, and tripping on a rate computed from two
 * samples does the same.
 *
 * <p>The implementation is lock-free on the fast path: an {@link ArrayDeque} of outcomes guarded by
 * the per-circuit monitor, mutated only on completion, never on the admission decision.
 *
 * @author Martin
 */
public final class JQuickCircuitBreaker {

    private final double failureRateThreshold;
    private final int windowSize;
    private final int minimumSamples;
    private final long openMillis;
    private final Map<String, Circuit> circuits = new ConcurrentHashMap<String, Circuit>();

    public JQuickCircuitBreaker(double failureRateThreshold, int windowSize,
                                int minimumSamples, long openMillis) {
        this.failureRateThreshold = clamp(failureRateThreshold);
        this.windowSize = windowSize <= 0 ? 20 : windowSize;
        this.minimumSamples = minimumSamples <= 0 ? 5 : minimumSamples;
        this.openMillis = openMillis <= 0 ? 10_000L : openMillis;
    }

    private static double clamp(double value) {
        if (value <= 0d) {
            return 0.5d;
        }
        return value >= 1d ? 1d : value;
    }

    /**
     * Whether a call may proceed right now.
     */
    public boolean allowRequest(String key) {
        return circuitFor(key).allowRequest(openMillis);
    }

    public void recordSuccess(String key) {
        circuitFor(key).record(true);
    }

    public void recordFailure(String key) {
        circuitFor(key).record(false);
    }

    public State stateOf(String key) {
        return circuitFor(key).state(openMillis);
    }

    public double failureRateOf(String key) {
        return circuitFor(key).failureRate();
    }

    public void reset() {
        circuits.clear();
    }

    private Circuit circuitFor(String key) {
        Circuit circuit = circuits.get(key);
        if (circuit != null) {
            return circuit;
        }
        Circuit created = new Circuit(windowSize, minimumSamples, failureRateThreshold);
        Circuit previous = circuits.putIfAbsent(key, created);
        return previous != null ? previous : created;
    }

    /**
     * Breaker state.
     */
    public enum State {

        /**
         * Calls flow through.
         */
        CLOSED,

        /**
         * Calls are refused immediately until the cool-down elapses.
         */
        OPEN,

        /**
         * One probe call is admitted to test the dependency.
         */
        HALF_OPEN
    }

    /**
     * One breaker instance.
     */
    private static final class Circuit {

        private final Deque<Boolean> outcomes;

        private final int windowSize;

        private final int minimumSamples;

        private final double threshold;

        private State state = State.CLOSED;

        private long openedAtNanos;

        Circuit(int windowSize, int minimumSamples, double threshold) {
            this.windowSize = windowSize;
            this.minimumSamples = minimumSamples;
            this.threshold = threshold;
            this.outcomes = new ArrayDeque<Boolean>(windowSize);
        }

        synchronized boolean allowRequest(long openMillis) {
            if (state == State.OPEN) {
                if (System.nanoTime() - openedAtNanos >= openMillis * 1_000_000L) {
                    state = State.HALF_OPEN;
                    return true;
                }
                return false;
            }
            return true;
        }

        synchronized void record(boolean success) {
            if (state == State.HALF_OPEN) {
                // A single probe decides the fate of the breaker.
                if (success) {
                    state = State.CLOSED;
                    outcomes.clear();
                } else {
                    trip();
                }
                return;
            }
            outcomes.addLast(success);
            while (outcomes.size() > windowSize) {
                outcomes.pollFirst();
            }
            if (outcomes.size() >= minimumSamples && failureRate() >= threshold) {
                trip();
            }
        }

        private void trip() {
            state = State.OPEN;
            openedAtNanos = System.nanoTime();
        }

        synchronized State state(long openMillis) {
            if (state == State.OPEN
                    && System.nanoTime() - openedAtNanos >= openMillis * 1_000_000L) {
                return State.HALF_OPEN;
            }
            return state;
        }

        synchronized double failureRate() {
            if (outcomes.isEmpty()) {
                return 0d;
            }
            int failures = 0;
            for (Boolean outcome : outcomes) {
                if (!outcome) {
                    failures++;
                }
            }
            return (double) failures / outcomes.size();
        }
    }
}
