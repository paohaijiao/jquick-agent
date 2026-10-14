package io.github.paohaijiao.jquick.agent.metrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One sample line of a metric.
 *
 * <p>A metric can expose several samples: a timer contributes a count, a sum and a maximum. Modelling
 * that explicitly (rather than letting each meter render its own text) is what allows the exporter to
 * emit the {@code # HELP} / {@code # TYPE} header exactly once per metric name, as the Prometheus
 * text format requires when several meters share a name and differ only by their labels.
 *
 * @author Martin
 */
public final class JQuickSample {

    /**
     * Suffix appended to the metric name, e.g. {@code "_count"}; empty for a single-sample meter.
     */
    private final String suffix;

    private final double value;

    public JQuickSample(String suffix, double value) {
        this.suffix = suffix == null ? "" : suffix;
        this.value = value;
    }

    public static List<JQuickSample> single(double value) {
        List<JQuickSample> samples = new ArrayList<JQuickSample>(1);
        samples.add(new JQuickSample("", value));
        return samples;
    }

    public static List<JQuickSample> empty() {
        return Collections.emptyList();
    }

    public String getSuffix() {
        return suffix;
    }

    public double getValue() {
        return value;
    }

    @Override
    public String toString() {
        return suffix + "=" + value;
    }
}
