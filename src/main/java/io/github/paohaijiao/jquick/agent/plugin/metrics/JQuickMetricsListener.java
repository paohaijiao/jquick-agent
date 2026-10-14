package io.github.paohaijiao.jquick.agent.plugin.metrics;

import io.github.paohaijiao.jquick.agent.core.JQuickMethodEvent;
import io.github.paohaijiao.jquick.agent.core.JQuickMethodListener;
import io.github.paohaijiao.jquick.agent.metrics.JQuickCounter;
import io.github.paohaijiao.jquick.agent.metrics.JQuickMeterRegistry;
import io.github.paohaijiao.jquick.agent.metrics.JQuickTimer;
import io.github.paohaijiao.jquick.agent.trace.JQuickSpan;

/**
 * Aggregates spans into the four numbers an on-call engineer looks at first.
 *
 * <table>
 *   <tr><th>Metric</th><th>Question it answers</th></tr>
 *   <tr><td>{@code jquick_method_calls_total}</td><td>How much traffic? (QPS when rate()'d)</td></tr>
 *   <tr><td>{@code jquick_method_errors_total}</td><td>How is it failing? (error rate against calls)</td></tr>
 *   <tr><td>{@code jquick_method_duration_seconds}</td><td>How slow is it, typically and at worst?</td></tr>
 *   <tr><td>{@code jquick_method_slow_calls_total}</td><td>How often does it breach the SLO?</td></tr>
 * </table>
 *
 * <p>The slow-call counter is what makes a "hot spot" visible without a profiler: it is a plain
 * counter, so it can be alerted on, and the threshold is a single configuration key
 * ({@code trace.slow.ms}).
 *
 * @author Martin
 */
public final class JQuickMetricsListener implements JQuickMethodListener {

    private final JQuickMeterRegistry meters;

    private final boolean byMethod;

    private final long slowThresholdMillis;

    public JQuickMetricsListener(JQuickMeterRegistry meters, boolean byMethod,
                                 long slowThresholdMillis) {
        this.meters = meters;
        this.byMethod = byMethod;
        this.slowThresholdMillis = slowThresholdMillis;
    }

    @Override
    public void onMethodEvent(JQuickMethodEvent event) {
        if (event.getPhase() == JQuickMethodEvent.JQuickPhase.ENTER) {
            return;
        }
        JQuickSpan span = event.getSpan();
        if (span == null) {
            return;
        }
        try {
            String label = byMethod ? span.getOperation() : span.getClassName();
            String type = span.getType() == null ? "LOCAL" : span.getType().name();
            meters.counter("jquick_method_calls_total", "target", label, "type", type).increment();
            JQuickTimer timer = meters.timer("jquick_method_duration_seconds",
                    "target", label, "type", type);
            timer.record(span.getDurationNanos());
            if (!span.isSuccess()) {
                meters.counter("jquick_method_errors_total", "target", label).increment();
            }
            if (span.getDurationMillis() >= slowThresholdMillis) {
                meters.counter("jquick_method_slow_calls_total", "target", label).increment();
            }
        } catch (Throwable ignored) {
            // Aggregation must never affect the application.
        }
    }

    /**
     * Exposes the number of aggregated latency series as a gauge, useful to detect label explosion.
     */
    public void registerCardinalityGauge() {
        meters.gauge("jquick_metrics_series", new io.github.paohaijiao.jquick.agent.metrics
                .JQuickGauge.ValueSource() {
            @Override
            public double read() {
                return meters.size();
            }
        });
    }

    /**
     * Convenience accessor used by the demos to print a snapshot.
     */
    public JQuickCounter callsFor(String target) {
        return meters.counter("jquick_method_calls_total", "target", target, "type", "LOCAL");
    }

    @Override
    public String name() {
        return "metrics-aggregator";
    }
}
