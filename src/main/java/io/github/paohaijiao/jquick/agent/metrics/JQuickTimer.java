package io.github.paohaijiao.jquick.agent.metrics;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Latency metric: how many observations, the total and the worst case.
 *
 * <p>This is the shape that answers the three questions an SRE actually asks about a dependency:
 * <em>how much traffic</em> (count), <em>how much time in total</em> (sum, which divided by count
 * gives the average and, added up, the throughput-weighted load), and <em>how bad does it get</em>
 * (max, which is the number that correlates with user complaints).
 *
 * <p>Only max is kept rather than a full histogram: a reservoir or a percentile sketch would add
 * allocation and locking to the hot path of every instrumented call, and the agent's contract is to
 * stay affordable. Deployments that need real percentiles should export the raw spans instead — the
 * topology and the trace listener already provide them.
 *
 * @author Martin
 */
public final class JQuickTimer implements JQuickMeter {

    private final String name;

    private final Map<String, String> tags;

    private final String help;

    private final LongAdder count = new LongAdder();

    private final LongAdder totalNanos = new LongAdder();

    private final AtomicLong maxNanos = new AtomicLong();

    private final AtomicLong minNanos = new AtomicLong(Long.MAX_VALUE);

    public JQuickTimer(String name, Map<String, String> tags, String help) {
        this.name = name;
        this.tags = tags == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(tags));
        this.help = help;
    }

    private static double nanosToSeconds(long nanos) {
        return nanos / 1_000_000_000d;
    }

    /**
     * Records one observation.
     */
    public void record(long nanos) {
        if (nanos < 0L) {
            return;
        }
        count.increment();
        totalNanos.add(nanos);
        updateMax(nanos);
        updateMin(nanos);
    }

    private void updateMax(long value) {
        long current = maxNanos.get();
        while (value > current) {
            if (maxNanos.compareAndSet(current, value)) {
                return;
            }
            current = maxNanos.get();
        }
    }

    private void updateMin(long value) {
        long current = minNanos.get();
        while (value < current) {
            if (minNanos.compareAndSet(current, value)) {
                return;
            }
            current = minNanos.get();
        }
    }

    public long count() {
        return count.sum();
    }

    public long totalNanos() {
        return totalNanos.sum();
    }

    public long maxNanos() {
        return maxNanos.get();
    }

    public long minNanos() {
        long value = minNanos.get();
        return value == Long.MAX_VALUE ? 0L : value;
    }

    /**
     * Mean duration in nanoseconds, or {@code 0} when nothing was recorded.
     */
    public long averageNanos() {
        long observations = count();
        return observations == 0L ? 0L : totalNanos() / observations;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Map<String, String> tags() {
        return tags;
    }

    @Override
    public String type() {
        return "summary";
    }

    @Override
    public String help() {
        return help;
    }

    /**
     * Emits {@code _count}, {@code _sum}, {@code _max} and {@code _min} in seconds, the unit
     * Prometheus and every Grafana dashboard expect.
     */
    @Override
    public List<JQuickSample> collect() {
        List<JQuickSample> samples = new ArrayList<JQuickSample>(4);
        samples.add(new JQuickSample("_count", count()));
        samples.add(new JQuickSample("_sum", nanosToSeconds(totalNanos())));
        samples.add(new JQuickSample("_max", nanosToSeconds(maxNanos())));
        samples.add(new JQuickSample("_min", nanosToSeconds(minNanos())));
        return samples;
    }

    @Override
    public void reset() {
        count.reset();
        totalNanos.reset();
        maxNanos.set(0L);
        minNanos.set(Long.MAX_VALUE);
    }

    @Override
    public String toString() {
        return name + tags + "{count=" + count() + ", avgNanos=" + averageNanos() + '}';
    }
}
