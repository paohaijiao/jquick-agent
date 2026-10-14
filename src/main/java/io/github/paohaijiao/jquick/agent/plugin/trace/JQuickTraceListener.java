package io.github.paohaijiao.jquick.agent.plugin.trace;

import io.github.paohaijiao.jquick.agent.core.JQuickMethodEvent;
import io.github.paohaijiao.jquick.agent.core.JQuickMethodListener;
import io.github.paohaijiao.jquick.agent.support.JQuickLogger;
import io.github.paohaijiao.jquick.agent.support.JQuickValueFormatter;
import io.github.paohaijiao.jquick.agent.trace.JQuickSpan;

/**
 * Renders completed spans as a trace log.
 *
 * <p>The output is deliberately line oriented and indented by span depth, so a trace can be read
 * directly, grepped by trace id, or piped into a log shipper. It is the smallest useful consumer of
 * the span stream and doubles as the reference implementation for anyone writing a listener that
 * ships spans to a real backend.
 *
 * <pre>
 * [jquick-agent] [4f2c...:9a1b...] http-nio-8080-exec-1
 *   &gt;&gt; com.demo.OrderController#pay(java.lang.String) [3ms]
 *     |- com.demo.OrderService#pay(java.lang.String) [2ms]
 *       &lt;&lt; com.demo.OrderDao#save(com.demo.Order) [1ms] component=jdbc
 * </pre>
 *
 * <p>An error span is printed with its exception type and message. The stack trace, the arguments
 * and the return value are each opt-in, because each one costs: {@code trace.error.stack=true} prints
 * the frames of a failure, {@code trace.args=true} prints what the call was made with, and
 * {@code trace.result=true} prints what it returned.
 *
 * <h3>The flags are not only about printing</h3>
 * {@link #captureArguments()} and {@link #captureResult()} are read by the enhancer <em>at
 * instrumentation time</em>, through the hook plan. Asking for them is therefore what makes the
 * emitter materialise the argument array and box the return value; without those two overrides this
 * listener would be handed an invocation that carries nothing to render.
 *
 * <p>A stack trace needs no such cooperation: the JVM already filled it in when the exception was
 * thrown, so reading it is a matter of policy (and of log volume) rather than of instrumentation cost.
 *
 * @author Martin
 */
public final class JQuickTraceListener implements JQuickMethodListener {

    private static final String TAG = JQuickLogger.TAG;

    private final boolean print;

    private final long slowThresholdMillis;

    private final boolean captureArguments;

    private final boolean captureResult;

    private final boolean captureStack;

    /**
     * Trace log with spans only; the cheapest useful configuration and the default.
     */
    public JQuickTraceListener(boolean print, long slowThresholdMillis) {
        this(print, slowThresholdMillis, false, false, false);
    }

    /**
     * Trace log with the payload the operator asked for.
     *
     * @param print               whether to print one line per span
     * @param slowThresholdMillis duration at or above which a span is flagged as slow
     * @param captureArguments    record and print the arguments of every instrumented call
     * @param captureResult       record and print the return value of every instrumented call
     * @param captureStack        print the stack trace of a failing call
     */
    public JQuickTraceListener(boolean print, long slowThresholdMillis, boolean captureArguments,
                               boolean captureResult, boolean captureStack) {
        this.print = print;
        this.slowThresholdMillis = slowThresholdMillis;
        this.captureArguments = captureArguments;
        this.captureResult = captureResult;
        this.captureStack = captureStack;
    }

    /**
     * Keeps trace output readable by shortening the 32/16 character ids to their first 8 characters.
     */
    private static String shortId(String value) {
        if (value == null) {
            return "-";
        }
        return value.length() <= 8 ? value : value.substring(0, 8);
    }

    @Override
    public boolean captureArguments() {
        return captureArguments;
    }

    @Override
    public boolean captureResult() {
        return captureResult;
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
        if (!print) {
            return;
        }
        try {
            StringBuilder line = new StringBuilder(192);
            line.append(TAG).append(" [").append(shortId(span.getTraceId()))
                    .append(':').append(shortId(span.getSpanId())).append("] ")
                    .append(span.getThreadName()).append('\n');
            line.append(span.format());
            appendPayload(line, event);
            if (span.getDurationMillis() >= slowThresholdMillis) {
                line.append("  <<< SLOW (>=").append(slowThresholdMillis).append("ms)");
            }
            System.out.println(line);
        } catch (Throwable ignored) {
            // Rendering must never affect the application.
        }
    }

    /**
     * Appends the arguments, the return value and — for a failure — the frames.
     *
     * <p>Everything is read from the invocation rather than from the span, because the invocation is
     * the description of the process boundary: it holds what the caller passed and what left the
     * method, after any transformation, which is exactly what explains the observed behaviour.
     */
    private void appendPayload(StringBuilder line, JQuickMethodEvent event) {
        if (captureArguments) {
            line.append("  args=").append(
                    JQuickValueFormatter.arguments(event.getInvocation().getArguments()));
        }
        if (captureResult) {
            Object result = event.getInvocation().getReturnValue();
            if (result != null || event.getPhase() == JQuickMethodEvent.JQuickPhase.EXIT) {
                line.append("  result=").append(JQuickValueFormatter.result(result));
            }
        }
        if (!captureStack || event.getError() == null) {
            return;
        }
        for (String frame : JQuickValueFormatter.stackTrace(event.getError())) {
            line.append("\n    ").append(frame);
        }
    }

    @Override
    public String name() {
        return "trace-log";
    }
}
