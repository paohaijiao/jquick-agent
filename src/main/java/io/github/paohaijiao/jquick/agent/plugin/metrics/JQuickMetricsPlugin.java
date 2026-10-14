package io.github.paohaijiao.jquick.agent.plugin.metrics;

import io.github.paohaijiao.jquick.agent.core.*;

import java.util.Collections;
import java.util.List;

/**
 * Capability: turns the span stream into Prometheus metrics (QPS, error rate, latency, slow calls).
 *
 * <p>This plugin injects <b>no byte-code at all</b>. It consumes the events the tracing plugin
 * already produces, which is the whole point of the listener abstraction: adding a metrics backend
 * costs one registration and zero additional runtime hooks.
 *
 * <h3>Cardinality</h3>
 * Metrics are labelled by <b>class</b> and span type, never by full method signature. Labelling by
 * signature is the classic way to blow up a Prometheus instance: a mid-sized codebase produces tens
 * of thousands of distinct signatures, each becoming a separate time series. Operators who really
 * need per-method metrics can switch {@code metrics.labels=method} and accept the footprint.
 *
 * @author Martin
 */
public final class JQuickMetricsPlugin implements JQuickPlugin {

    public static final String NAME = "metrics";

    /**
     * Label mode: {@code class} (default) or {@code method}.
     */
    public static final String LABELS = "metrics.labels";

    /**
     * Whether the built-in Prometheus HTTP endpoint is started and on which port.
     */
    public static final String PORT = JQuickConfig.METRICS_PORT;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public JQuickCategory category() {
        return JQuickCategory.METRICS;
    }

    @Override
    public boolean isEnabled(JQuickConfig config) {
        // Metrics are observational and cheap, so they are on by default; the HTTP endpoint is not.
        return config.getBoolean(JQuickConfig.METRICS_ENABLED, true);
    }

    @Override
    public void initialize(JQuickEnvironment environment) {
        boolean byMethod = "method".equalsIgnoreCase(
                environment.getConfig().get(LABELS, "class"));
        environment.addListener(new JQuickMetricsListener(
                environment.getMeters(),
                byMethod,
                environment.getSlowThresholdMillis()));
    }

    @Override
    public List<JQuickClassEnhancer> enhancers() {
        return Collections.emptyList();
    }

    @Override
    public String description() {
        return "Prometheus metrics: calls, errors, latency, slow calls";
    }
}
