package io.github.paohaijiao.jquick.agent.plugin.log;

import io.github.paohaijiao.jquick.agent.trace.JQuickTraceContext;

/**
 * Application-facing helpers for putting the trace id into the log stream.
 *
 * <p>Two routes exist and both are supported:
 * <ul>
 *   <li><b>the agent does it</b> — {@link JQuickLogPlugin} rewrites the message argument of the
 *       logging call, so an application that does nothing at all still gets the id in every line;</li>
 *   <li><b>the application does it</b> — this class formats the prefix, for teams that would rather
 *       change a logging pattern than let an agent touch a logging call.</li>
 * </ul>
 *
 * <h3>Why not MDC</h3>
 * Writing to MDC is the conventional answer, but the agent cannot safely do it: MDC lives in whatever
 * logging implementation the application happens to use, which the agent must not load (that would
 * create a class-loading dependency from the agent into the application). Reading a thread-local that
 * the agent already owns is both simpler and immune to that problem.
 *
 * @author Martin
 */
public final class JQuickLogSupport {

    private JQuickLogSupport() {
    }

    /**
     * The current trace id, or {@code null}.
     */
    public static String traceId() {
        return JQuickTraceContext.getTraceId();
    }

    /**
     * The current span id, or {@code null}.
     */
    public static String spanId() {
        return JQuickTraceContext.getSpanId();
    }

    /**
     * A {@code [traceId]} prefix, or an empty string when the thread is not tracing.
     */
    public static String prefix() {
        String traceId = JQuickTraceContext.getTraceId();
        return traceId == null ? "" : "[" + traceId + "] ";
    }

    /**
     * Prefixes a message with the trace id when one is active.
     */
    public static String decorate(String message) {
        if (message == null) {
            return prefix();
        }
        String prefix = prefix();
        return prefix.isEmpty() ? message : prefix + message;
    }

    /**
     * A logging pattern fragment that renders the trace id from the MDC, for teams that would rather
     * change a pattern than let an agent rewrite a message.
     *
     * <p>It only works if <em>something</em> populates the key: the agent deliberately does not write
     * to MDC (see the class comment), so the application must put the value there itself — for
     * example with a servlet filter calling
     * {@code MDC.put("jquick-trace-id", JQuickLogSupport.traceId())}. Use {@link #decorate(String)}
     * instead when the agent should do the work.
     */
    public static String patternFragment() {
        return "[%X{jquick-trace-id}]";
    }
}
