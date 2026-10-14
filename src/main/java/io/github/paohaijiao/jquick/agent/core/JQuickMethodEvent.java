package io.github.paohaijiao.jquick.agent.core;

import io.github.paohaijiao.jquick.agent.trace.JQuickSpan;

/**
 * Event published by the runtime sink for every instrumented call.
 *
 * @author Martin
 */
public final class JQuickMethodEvent {

    private final JQuickPhase phase;
    private final JQuickInvocation invocation;
    private final JQuickSpan span;
    private final long elapsedNanos;
    private final Throwable error;

    public JQuickMethodEvent(JQuickPhase phase, JQuickInvocation invocation,
                             JQuickSpan span, long elapsedNanos, Throwable error) {
        this.phase = phase;
        this.invocation = invocation;
        this.span = span;
        this.elapsedNanos = elapsedNanos;
        this.error = error;
    }

    public JQuickPhase getPhase() {
        return phase;
    }

    public JQuickInvocation getInvocation() {
        return invocation;
    }

    /**
     * The completed span; available for {@link JQuickPhase#EXIT} and {@link JQuickPhase#ERROR}.
     */
    public JQuickSpan getSpan() {
        return span;
    }

    /**
     * Elapsed time in nanoseconds; meaningful for EXIT and ERROR.
     */
    public long getElapsedNanos() {
        return elapsedNanos;
    }

    public long getElapsedMillis() {
        return elapsedNanos / 1_000_000L;
    }

    public Throwable getError() {
        return error;
    }

    public String getSignature() {
        return invocation.getSignature();
    }

    public String getClassName() {
        return invocation.getClassName();
    }

    public String getMethodName() {
        return invocation.getMethodName();
    }

    public String getTraceId() {
        return invocation.getTraceId();
    }

    public String getSpanId() {
        return invocation.getSpanId();
    }

    /**
     * The span that encloses this call, or {@code null} at the root of a trace.
     */
    public String getParentSpanId() {
        return invocation.getParentSpanId();
    }

    @Override
    public String toString() {
        return "JQuickMethodEvent{" + phase + " " + invocation.getSignature()
                + (phase == JQuickPhase.ENTER ? "" : " [" + elapsedNanos + "ns]")
                + (error == null ? "" : " error=" + error) + '}';
    }

    /**
     * Lifecycle phase of an instrumented call.
     */
    public enum JQuickPhase {

        /**
         * The method body is about to execute.
         */
        ENTER,

        /**
         * The method returned normally.
         */
        EXIT,

        /**
         * The method left through a thrown {@code Throwable}.
         */
        ERROR
    }
}
