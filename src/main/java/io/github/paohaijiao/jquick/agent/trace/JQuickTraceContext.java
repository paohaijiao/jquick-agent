package io.github.paohaijiao.jquick.agent.trace;

import io.github.paohaijiao.jquick.agent.support.JQuickIdGenerator;

/**
 * Public facade over the thread-bound trace state.
 *
 * <p>This is the class application code, log appenders and the plugins talk to. It hides
 * {@link JQuickTraceScope} (an implementation detail) and is safe to call from any thread at any
 * time, including before the agent is installed — in that case it simply creates an independent
 * trace, which keeps the API usable in unit tests and in offline tooling.
 *
 * @author Martin
 */
public final class JQuickTraceContext {

    private static final ThreadLocal<JQuickTraceScope> HOLDER = new ThreadLocal<JQuickTraceScope>() {
        @Override
        protected JQuickTraceScope initialValue() {
            return new JQuickTraceScope();
        }
    };

    private JQuickTraceContext() {
    }

    /**
     * The scope of the calling thread; never {@code null}.
     */
    public static JQuickTraceScope scope() {
        return HOLDER.get();
    }

    /**
     * Current trace id, or {@code null} when this thread is not tracing.
     */
    public static String getTraceId() {
        return HOLDER.get().getTraceId();
    }

    /**
     * Forces a trace id on the calling thread (used when a context is propagated or injected by an
     * upstream caller through an HTTP header).
     */
    public static void setTraceId(String traceId) {
        JQuickTraceScope scope = HOLDER.get();
        if (traceId == null) {
            scope.reset();
        } else {
            scope.setTraceId(traceId);
        }
    }

    /**
     * Current span id, or {@code null} when no span is open.
     */
    public static String getSpanId() {
        return HOLDER.get().currentSpanId();
    }

    public static String getParentSpanId() {
        return scope().parentOfCurrentSpan();
    }

    public static boolean isActive() {
        return HOLDER.get().isActive();
    }

    /**
     * Returns the current trace id, creating one when absent.
     */
    public static String getOrCreateTraceId() {
        JQuickTraceScope scope = HOLDER.get();
        if (scope.getTraceId() == null) {
            scope.setTraceId(JQuickIdGenerator.newTraceId());
        }
        return scope.getTraceId();
    }

    /**
     * Removes every trace artefact of the calling thread. Mandatory after a pooled task finishes,
     * otherwise the next task picked up by the same worker inherits a finished trace.
     */
    public static void clear() {
        HOLDER.remove();
    }

    /**
     * Captures the calling thread context for cross-thread propagation.
     */
    public static JQuickTraceSnapshot capture() {
        return HOLDER.get().snapshot();
    }

    /**
     * Restores a previously captured context on the calling thread.
     */
    public static void restore(JQuickTraceSnapshot snapshot) {
        HOLDER.get().restore(snapshot);
    }

    /**
     * Renders the standard propagation header value, {@code traceId:spanId:parentSpanId}, which is
     * the format accepted by Spring Cloud Sleuth, Brave and Zipkin.
     */
    public static String formatHeader() {
        JQuickTraceScope scope = HOLDER.get();
        String traceId = scope.getTraceId();
        if (traceId == null) {
            return null;
        }
        String spanId = scope.currentSpanId();
        String parentId = scope.parentOfCurrentSpan();
        return traceId + ":" + (spanId == null ? "" : spanId)
                + ":" + (parentId == null ? "" : parentId)
                + ":" + (scope.isSampled() ? "1" : "0");
    }
}
