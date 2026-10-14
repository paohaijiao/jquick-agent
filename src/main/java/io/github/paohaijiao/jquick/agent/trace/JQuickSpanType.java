package io.github.paohaijiao.jquick.agent.trace;

/**
 * Role of a span inside the call graph.
 *
 * <p>The three values mirror the terminology used by SkyWalking and Jaeger, and they are what makes
 * a topology reconstructible from a flat stream of spans:
 *
 * <ul>
 *   <li>{@link #ENTRY} — a server side edge: an inbound HTTP request, an MQ consumer, a scheduled
 *       job. It starts a new trace when none is active, and it is the only kind of span that counts
 *       towards QPS of a service.</li>
 *   <li>{@link #EXIT} — a client side edge: a JDBC statement, a Redis command, an RPC call to
 *       another service. It points at an external dependency, which is what the topology draws as
 *       a separate node.</li>
 *   <li>{@link #LOCAL} — an in-process method call. Cheap, plentiful, and the raw material for
 *       "where did the time go" analysis and hot-spot detection.</li>
 * </ul>
 *
 * @author Martin
 */
public enum JQuickSpanType {

    ENTRY,

    EXIT,

    LOCAL;

    /**
     * Parses a configuration value; unknown or blank input falls back to {@link #LOCAL}.
     */
    public static JQuickSpanType parse(String value) {
        if (value == null) {
            return LOCAL;
        }
        String normalized = value.trim().toUpperCase();
        for (JQuickSpanType type : values()) {
            if (type.name().equals(normalized)) {
                return type;
            }
        }
        return LOCAL;
    }

    /**
     * Priority used when two plugins tag the same method; a more specific edge wins over LOCAL.
     */
    public int priority() {
        switch (this) {
            case ENTRY:
                return 2;
            case EXIT:
                return 1;
            default:
                return 0;
        }
    }
}
