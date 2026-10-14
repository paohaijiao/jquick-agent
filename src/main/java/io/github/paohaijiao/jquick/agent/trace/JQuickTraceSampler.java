package io.github.paohaijiao.jquick.agent.trace;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Trace sampling policy.
 *
 * <p>Sampling is what keeps an always-on agent affordable in production: at a 10% rate per new
 * trace, the storage and the per-call cost of the downstream pipeline drop by roughly the same
 * factor, while the structural information (which endpoints exist, where the time goes) stays
 * statistically intact.
 *
 * <p>The decision is taken once, when a <em>new</em> trace starts, and is then propagated through
 * {@link JQuickTraceSnapshot}, so a sampled trace is sampled consistently on every thread it
 * touches.
 *
 * @author Martin
 */
public final class JQuickTraceSampler {

    /**
     * Samples everything. Used when sampling is disabled (the default).
     */
    public static final JQuickTraceSampler ALWAYS = new JQuickTraceSampler(1.0d);

    private final double rate;

    private JQuickTraceSampler(double rate) {
        this.rate = rate < 0d ? 0d : (rate > 1d ? 1d : rate);
    }

    /**
     * Creates a probabilistic sampler. A rate of {@code 1} disables sampling, {@code 0.1} keeps one
     * trace out of ten.
     */
    public static JQuickTraceSampler probabilistic(double rate) {
        if (rate >= 1d) {
            return ALWAYS;
        }
        return new JQuickTraceSampler(rate);
    }

    public double getRate() {
        return rate;
    }

    /**
     * Decides whether a newly started trace should be recorded.
     */
    public boolean shouldSample() {
        if (rate >= 1d) {
            return true;
        }
        if (rate <= 0d) {
            return false;
        }
        return ThreadLocalRandom.current().nextDouble() < rate;
    }

    @Override
    public String toString() {
        return "JQuickTraceSampler{rate=" + rate + '}';
    }
}
