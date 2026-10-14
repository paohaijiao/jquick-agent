package io.github.paohaijiao.jquick.agent.metrics;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A metric whose value is observed on demand rather than recorded.
 *
 * <p>Used for the quantities that are already maintained somewhere else — number of loaded classes,
 * size of the trace registry, number of jar conflicts — where duplicating the bookkeeping would cost
 * more than reading it at scrape time.
 *
 * @author Martin
 */
public final class JQuickGauge implements JQuickMeter {

    private final String name;
    private final Map<String, String> tags;
    private final String help;
    private final AtomicReference<ValueSource> source = new AtomicReference<ValueSource>();

    public JQuickGauge(String name, Map<String, String> tags, String help, ValueSource source) {
        this.name = name;
        this.tags = tags == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(tags));
        this.help = help;
        this.source.set(source);
    }

    /**
     * Atomically replaces the value source.
     */
    public void setSource(ValueSource replacement) {
        source.set(replacement);
    }

    /**
     * Sets a fixed value.
     */
    public void set(double value) {
        final double captured = value;
        source.set(new ValueSource() {
            @Override
            public double read() {
                return captured;
            }
        });
    }

    public double value() {
        ValueSource current = source.get();
        if (current == null) {
            return 0d;
        }
        try {
            return current.read();
        } catch (Throwable ignored) {
            // A broken gauge must never break the scrape.
            return 0d;
        }
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
        return "gauge";
    }

    @Override
    public String help() {
        return help;
    }

    @Override
    public List<JQuickSample> collect() {
        return JQuickSample.single(value());
    }

    @Override
    public void reset() {
        source.set(null);
    }

    @Override
    public String toString() {
        return name + tags + "=" + value();
    }

    /**
     * Supplies the current value; invoked on every scrape.
     */
    public interface ValueSource {

        double read();
    }
}
