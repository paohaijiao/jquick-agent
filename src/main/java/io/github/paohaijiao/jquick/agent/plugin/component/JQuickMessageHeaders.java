package io.github.paohaijiao.jquick.agent.plugin.component;

import io.github.paohaijiao.jquick.agent.trace.JQuickTraceContext;
import io.github.paohaijiao.jquick.agent.trace.JQuickTraceScope;

import java.util.Map;

/**
 * Trace propagation across a message broker, an HTTP hop or any other boundary that carries metadata.
 *
 * <p>A trace only survives a process boundary if the identifiers travel with the message. This class
 * owns the wire contract — three header names — and is the only place that knows them, so a producer
 * and a consumer written against it stay compatible even if the underlying format evolves.
 *
 * <pre>{@code
 * // producer
 * JQuickMessageHeaders.applyTo(message.getHeaders());
 *
 * // consumer
 * if (JQuickMessageHeaders.extract(message.getHeaders())) {
 *     // the rest of the trace now continues on this thread
 * }
 * }</pre>
 *
 * @author Martin
 */
public final class JQuickMessageHeaders {

    /**
     * Carries the 128-bit trace id.
     */
    public static final String TRACE_ID = "jquick-trace-id";

    /**
     * Carries the id of the span that produced the message.
     */
    public static final String SPAN_ID = "jquick-span-id";

    /**
     * Carries the parent span id, so a consumer can rebuild the caller relationship.
     */
    public static final String PARENT_SPAN_ID = "jquick-parent-span-id";

    private JQuickMessageHeaders() {
    }

    /**
     * Writes the current trace context into the supplied header map.
     *
     * @return {@code true} when a trace was active and the headers were written
     */
    public static boolean applyTo(Map<String, String> headers) {
        if (headers == null) {
            return false;
        }
        JQuickTraceScope scope = JQuickTraceContext.scope();
        if (!scope.isActive()) {
            return false;
        }
        headers.put(TRACE_ID, nullSafe(scope.getTraceId()));
        headers.put(SPAN_ID, nullSafe(scope.currentSpanId()));
        headers.put(PARENT_SPAN_ID, nullSafe(scope.parentOfCurrentSpan()));
        return true;
    }

    /**
     * Restores a trace context from the supplied headers.
     *
     * @return {@code true} when the headers carried a trace id and the context was restored
     */
    public static boolean extract(Map<String, String> headers) {
        if (headers == null) {
            return false;
        }
        String traceId = headers.get(TRACE_ID);
        if (traceId == null || traceId.isEmpty()) {
            return false;
        }
        JQuickTraceScope scope = JQuickTraceContext.scope();
        scope.setTraceId(traceId);
        String remoteSpanId = headers.get(SPAN_ID);
        if (remoteSpanId != null && !remoteSpanId.isEmpty()) {
            // Re-push the remote span so the consumer's first span is a child of the producer's.
            scope.beginSpan(remoteSpanId);
        }
        return true;
    }

    /**
     * Clears the trace context of the calling thread.
     */
    public static void clear() {
        JQuickTraceContext.clear();
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
