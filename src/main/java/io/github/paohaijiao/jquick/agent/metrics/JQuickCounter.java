package io.github.paohaijiao.jquick.agent.metrics;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * Monotonically increasing metric, e.g. "how many times was this endpoint called, and how many of
 * those calls failed".
 *
 * <p>Backed by {@link LongAdder} rather than {@code AtomicLong}: the agent increments counters from
 * every instrumented thread, which is exactly the high-contention write-mostly pattern
 * {@code LongAdder} was designed for and where it is measurably faster than a CAS loop.
 *
 * @author Martin
 */
public final class JQuickCounter implements JQuickMeter {

    private final String name;

    private final Map<String, String> tags;

    private final String help;

    private final LongAdder adder = new LongAdder();

    public JQuickCounter(String name, Map<String, String> tags, String help) {
        this.name = name;
        this.tags = tags == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(tags));
        this.help = help;
    }

    public void increment() {
        adder.increment();
    }

    public void add(long delta) {
        if (delta != 0L) {
            adder.add(delta);
        }
    }

    public long count() {
        return adder.sum();
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
        return "counter";
    }

    @Override
    public String help() {
        return help;
    }

    @Override
    public List<JQuickSample> collect() {
        return JQuickSample.single(count());
    }

    @Override
    public void reset() {
        adder.reset();
    }

    @Override
    public String toString() {
        return name + tags + "=" + count();
    }
}
