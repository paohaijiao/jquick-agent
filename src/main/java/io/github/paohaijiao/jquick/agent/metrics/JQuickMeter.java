package io.github.paohaijiao.jquick.agent.metrics;

import java.util.List;
import java.util.Map;

/**
 * A named, labelled time series exposed to the monitoring pipeline.
 *
 * <p>The vocabulary is deliberately that of Prometheus ({@code counter}, {@code gauge},
 * {@code summary}) rather than a bespoke one, because the agent's job is to <em>feed</em> an existing
 * observability stack — Prometheus, VictoriaMetrics, Grafana Agent, OpenTelemetry collector — not to
 * invent a new one.
 *
 * @author Martin
 */
public interface JQuickMeter {

    /**
     * Metric name, already sanitised for the exposition format (dots replaced by underscores).
     */
    String name();

    /**
     * Labels of this series.
     */
    Map<String, String> tags();

    /**
     * Prometheus type: {@code counter}, {@code gauge} or {@code summary}.
     */
    String type();

    /**
     * One-line description emitted as {@code # HELP}.
     */
    String help();

    /**
     * Current samples.
     */
    List<JQuickSample> collect();

    /**
     * Clears the accumulated state. Used by tests and by operators rotating a measurement window.
     */
    void reset();
}
