package io.github.paohaijiao.jquick.agent.topology;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Call-graph accumulator: which component called which, how often, and how it went.
 *
 * <p>Topology is the one piece of information a flat metric stream cannot reconstruct and that a
 * trace stream only gives implicitly. Recording it online means an operator can answer "who talks to
 * the payment service" without deploying a collector and a graph database — the price is one map
 * lookup per <em>external</em> call (in-process calls are not edges, or the graph would be as large
 * as the codebase).
 *
 * <p>Only outgoing edges ({@code EXIT} spans) are recorded. The caller is taken from the enclosing
 * frame, so the graph is rooted in real application entry points rather than in whatever thread
 * happened to make the call.
 *
 * @author Martin
 */
public final class JQuickTopology {

    private final Map<String, Edge> edges = new ConcurrentHashMap<String, Edge>();

    /**
     * Records one completed outgoing call.
     *
     * @param caller        operation that made the call; {@code "(uninstrumented)"} when unknown
     * @param callee        operation that was called
     * @param component     logical dependency, e.g. {@code jdbc} or {@code redis}
     * @param durationNanos observed duration
     * @param success       whether the call completed normally
     */
    public void record(String caller, String callee, String component,
                       long durationNanos, boolean success) {
        String from = caller == null ? "(uninstrumented)" : caller;
        String to = callee == null ? "(unknown)" : callee;
        String key = from + '\u0001' + to + '\u0001' + (component == null ? "" : component);
        Edge edge = edges.get(key);
        if (edge == null) {
            Edge created = new Edge(from, to, component);
            Edge previous = edges.putIfAbsent(key, created);
            edge = previous != null ? previous : created;
        }
        edge.record(durationNanos, success);
    }

    public List<Edge> edges() {
        List<Edge> snapshot = new ArrayList<Edge>(edges.values());
        Collections.sort(snapshot);
        return snapshot;
    }

    public int size() {
        return edges.size();
    }

    /**
     * Renders the graph as an indented text report, grouped by caller.
     */
    public String render() {
        List<Edge> snapshot = edges();
        if (snapshot.isEmpty()) {
            return "(no external calls recorded yet)";
        }
        StringBuilder sb = new StringBuilder(1024);
        String currentCaller = null;
        for (Edge edge : snapshot) {
            if (!edge.getCaller().equals(currentCaller)) {
                currentCaller = edge.getCaller();
                sb.append(currentCaller).append('\n');
            }
            sb.append("    -> ").append(edge.getCallee())
                    .append(" [component=").append(edge.getComponent() == null ? "-" : edge.getComponent()).append(']')
                    .append(" calls=").append(edge.getCalls())
                    .append(" avgMs=").append(edge.averageMillis())
                    .append(" maxMs=").append(edge.maxMillis())
                    .append(" errors=").append(edge.getErrors())
                    .append('\n');
        }
        return sb.toString();
    }

    public void reset() {
        edges.clear();
    }

    /**
     * One directed edge of the graph, with its aggregate statistics.
     */
    public static final class Edge implements Comparable<Edge> {

        private final String caller;

        private final String callee;

        private final String component;

        private final LongAdder calls = new LongAdder();

        private final LongAdder errors = new LongAdder();

        private final LongAdder totalNanos = new LongAdder();

        private volatile long maxNanos;

        Edge(String caller, String callee, String component) {
            this.caller = caller;
            this.callee = callee;
            this.component = component;
        }

        private static String escape(String value) {
            return value.replace("\\", "\\\\").replace("\"", "\\\"");
        }

        void record(long durationNanos, boolean success) {
            calls.increment();
            if (durationNanos > 0L) {
                totalNanos.add(durationNanos);
                if (durationNanos > maxNanos) {
                    maxNanos = durationNanos;
                }
            }
            if (!success) {
                errors.increment();
            }
        }

        public String getCaller() {
            return caller;
        }

        public String getCallee() {
            return callee;
        }

        public String getComponent() {
            return component;
        }

        public long getCalls() {
            return calls.sum();
        }

        public long getErrors() {
            return errors.sum();
        }

        public long averageMillis() {
            long total = calls.sum();
            return total == 0L ? 0L : (totalNanos.sum() / total) / 1_000_000L;
        }

        public long maxMillis() {
            return maxNanos / 1_000_000L;
        }

        /**
         * Renders one edge as a Prometheus sample line.
         *
         * <p>The name carries the {@code _total} suffix because this is a counter and the text
         * exposition format requires the name to match the {@code # TYPE} header exactly — which is
         * emitted once for the whole family by the exporter.
         */
        public String toPrometheusLine() {
            return "jquick_topology_calls_total{caller=\"" + escape(caller)
                    + "\",callee=\"" + escape(callee)
                    + "\",component=\"" + escape(component == null ? "" : component)
                    + "\"} " + getCalls();
        }

        @Override
        public int compareTo(Edge other) {
            int byCaller = caller.compareTo(other.caller);
            if (byCaller != 0) {
                return byCaller;
            }
            return callee.compareTo(other.callee);
        }

        @Override
        public String toString() {
            return caller + " -> " + callee + " (" + getCalls() + " calls)";
        }
    }
}
