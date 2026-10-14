package io.github.paohaijiao.jquick.agent.metrics;

import io.github.paohaijiao.jquick.agent.support.JQuickLogger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of every metric the agent exposes.
 *
 * <p>Meters are created on first use and keyed by <em>name plus the full label set</em>, so two
 * plugins asking for {@code jquick_method_calls{method="a"}} and
 * {@code jquick_method_calls{method="b"}} transparently share one metric with two series — the
 * behaviour Prometheus expects.
 *
 * <p>The registry is also the place where Prometheus naming rules are enforced: dots and dashes are
 * rewritten to underscores and label values are escaped, so a plugin can use a natural
 * {@code jquick.jdbc.slow.queries} identifier without knowing anything about the exposition format.
 *
 * @author Martin
 */
public final class JQuickMeterRegistry {

    private final Map<String, JQuickMeter> meters = new ConcurrentHashMap<String, JQuickMeter>();

    private static List<JQuickSample> safeCollect(JQuickMeter meter) {
        try {
            return meter.collect();
        } catch (Throwable ex) {
            JQuickLogger.suppressed("metric '" + meter.name() + "' failed to collect", ex);
            return JQuickSample.empty();
        }
    }

    /**
     * Converts alternating key/value pairs into a label map.
     */
    private static Map<String, String> toLabels(String... tags) {
        if (tags == null || tags.length == 0) {
            return Collections.emptyMap();
        }
        Map<String, String> labels = new LinkedHashMap<String, String>();
        for (int i = 0; i + 1 < tags.length; i += 2) {
            if (tags[i] != null && tags[i + 1] != null) {
                labels.put(tags[i], tags[i + 1]);
            }
        }
        return labels;
    }

    private static String key(String name, Map<String, String> labels) {
        StringBuilder sb = new StringBuilder(name);
        for (Map.Entry<String, String> label : new TreeMap<String, String>(labels).entrySet()) {
            sb.append('\u0001').append(label.getKey()).append('=').append(label.getValue());
        }
        return sb.toString();
    }

    /**
     * Applies the Prometheus metric-name rules: letters, digits, underscores and colons only, and it
     * must not start with a digit.
     */
    static String sanitize(String name) {
        if (name == null || name.isEmpty()) {
            return "jquick_unnamed";
        }
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean valid = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == ':';
            sb.append(valid ? c : '_');
        }
        char first = sb.charAt(0);
        if (first >= '0' && first <= '9') {
            sb.insert(0, '_');
        }
        return sb.toString();
    }

    private static String formatLabels(Map<String, String> labels) {
        if (labels == null || labels.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> label : new TreeMap<String, String>(labels).entrySet()) {
            if (!first) {
                sb.append(',');
            }
            sb.append(label.getKey()).append("=\"").append(escape(label.getValue())).append('"');
            first = false;
        }
        return sb.append('}').toString();
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\':
                    sb.append("\\\\");
                    break;
                case '"':
                    sb.append("\\\"");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                default:
                    sb.append(c);
                    break;
            }
        }
        return sb.toString();
    }

    /**
     * Renders a {@code double} the way the exposition format wants it: integers without a decimal
     * point, infinities and NaN spelled out.
     */
    private static String formatValue(double value) {
        if (Double.isNaN(value)) {
            return "NaN";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "+Inf" : "-Inf";
        }
        if (value == Math.floor(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    /**
     * Returns (creating it if needed) a counter.
     *
     * @param name metric name, dots allowed
     * @param tags alternating key/value pairs, e.g. {@code "method", "pay", "status", "ok"}
     */
    public JQuickCounter counter(String name, String... tags) {
        String metricName = sanitize(name);
        Map<String, String> labels = toLabels(tags);
        String key = key(metricName, labels);
        JQuickMeter existing = meters.get(key);
        if (existing instanceof JQuickCounter) {
            return (JQuickCounter) existing;
        }
        JQuickCounter created = new JQuickCounter(metricName, labels, "jquick agent counter");
        JQuickMeter previous = meters.putIfAbsent(key, created);
        return previous instanceof JQuickCounter ? (JQuickCounter) previous : created;
    }

    /**
     * Returns (creating it if needed) a latency timer.
     */
    public JQuickTimer timer(String name, String... tags) {
        String metricName = sanitize(name);
        Map<String, String> labels = toLabels(tags);
        String key = key(metricName, labels);
        JQuickMeter existing = meters.get(key);
        if (existing instanceof JQuickTimer) {
            return (JQuickTimer) existing;
        }
        JQuickTimer created = new JQuickTimer(metricName, labels, "jquick agent latency");
        JQuickMeter previous = meters.putIfAbsent(key, created);
        return previous instanceof JQuickTimer ? (JQuickTimer) previous : created;
    }

    /**
     * Returns (creating it if needed) a gauge backed by {@code source}.
     */
    public JQuickGauge gauge(String name, JQuickGauge.ValueSource source, String... tags) {
        String metricName = sanitize(name);
        Map<String, String> labels = toLabels(tags);
        String key = key(metricName, labels);
        JQuickMeter existing = meters.get(key);
        if (existing instanceof JQuickGauge) {
            JQuickGauge gauge = (JQuickGauge) existing;
            gauge.setSource(source);
            return gauge;
        }
        JQuickGauge created = new JQuickGauge(metricName, labels, "jquick agent gauge", source);
        JQuickMeter previous = meters.putIfAbsent(key, created);
        return previous instanceof JQuickGauge ? (JQuickGauge) previous : created;
    }

    public Collection<JQuickMeter> meters() {
        return Collections.unmodifiableCollection(meters.values());
    }

    public int size() {
        return meters.size();
    }

    /**
     * Renders every meter in the Prometheus text exposition format.
     *
     * <p>{@code # HELP} and {@code # TYPE} are emitted once per metric name; the individual series
     * follow. This is the layout a Prometheus scrape endpoint must produce when several series share
     * a metric name.
     */
    public String scrape() {
        Map<String, List<JQuickMeter>> byName = new TreeMap<String, List<JQuickMeter>>();
        for (JQuickMeter meter : meters.values()) {
            List<JQuickMeter> group = byName.get(meter.name());
            if (group == null) {
                group = new ArrayList<JQuickMeter>();
                byName.put(meter.name(), group);
            }
            group.add(meter);
        }
        StringBuilder sb = new StringBuilder(2048);
        for (Map.Entry<String, List<JQuickMeter>> entry : byName.entrySet()) {
            String name = entry.getKey();
            List<JQuickMeter> group = entry.getValue();
            JQuickMeter head = group.get(0);
            sb.append("# HELP ").append(name).append(' ').append(head.help()).append('\n');
            sb.append("# TYPE ").append(name).append(' ').append(head.type()).append('\n');
            for (JQuickMeter meter : group) {
                List<JQuickSample> samples = safeCollect(meter);
                for (JQuickSample sample : samples) {
                    sb.append(name).append(sample.getSuffix())
                            .append(formatLabels(meter.tags()))
                            .append(' ').append(formatValue(sample.getValue()))
                            .append('\n');
                }
            }
        }
        return sb.toString();
    }

    public void reset() {
        meters.clear();
    }
}
