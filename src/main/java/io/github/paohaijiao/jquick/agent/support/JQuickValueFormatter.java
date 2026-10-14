package io.github.paohaijiao.jquick.agent.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Renders values for telemetry: method arguments, a return value, and a stack trace.
 *
 * <h3>Why this is a shared component</h3>
 * Three capabilities need exactly the same text — the audit sink records what a call was made with,
 * the trace log prints the same arguments beside the span, and the logging enhancer masks the same
 * kind of value. Three private copies of a truncation rule is three different ways for one of them to
 * write a 40 MB string into a log file.
 *
 * <h3>Truncation is the whole point</h3>
 * Telemetry must never be able to turn a large payload into an incident. So the number of arguments is
 * capped, every rendered value is capped, and the stack depth is capped. A truncated value is marked
 * with an ellipsis rather than silently shortened, because "the log shows a prefix" and "the argument
 * really was that short" must not look the same to someone debugging an incident.
 *
 * @author Martin
 */
public final class JQuickValueFormatter {

    /**
     * Maximum number of arguments rendered before the rest are summarised.
     */
    public static final int MAX_ARGUMENTS = 16;

    /**
     * Maximum length of a single rendered value.
     */
    public static final int MAX_VALUE_LENGTH = 128;

    /**
     * Maximum number of stack frames rendered.
     */
    public static final int MAX_STACK_FRAMES = 16;

    private JQuickValueFormatter() {
    }

    /**
     * Renders an argument array, e.g. {@code [alice, -1]}.
     *
     * @return a bracketed rendering; {@code "[]"} for {@code null} or an empty array
     */
    public static String arguments(Object[] arguments) {
        return arguments(arguments, MAX_ARGUMENTS, MAX_VALUE_LENGTH);
    }

    /**
     * Renders an argument array with explicit caps.
     */
    public static String arguments(Object[] arguments, int maxArguments, int maxValueLength) {
        if (arguments == null || arguments.length == 0) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        int rendered = Math.min(arguments.length, maxArguments);
        for (int i = 0; i < rendered; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(value(arguments[i], maxValueLength));
        }
        if (arguments.length > rendered) {
            sb.append(", ... ").append(arguments.length - rendered).append(" more");
        }
        return sb.append(']').toString();
    }

    /**
     * Renders a single value: a string (truncated), a primitive wrapper, or the type name of anything
     * else.
     *
     * <p>A non-primitive object is rendered as its <b>type</b>, never as {@code toString()}. Calling
     * {@code toString()} on an arbitrary argument would run arbitrary application code from inside the
     * agent, on the request path, with the argument's own side effects — and it is the single easiest
     * way for a tracing agent to change the behaviour it is supposed to be observing.
     */
    public static String value(Object argument, int maxValueLength) {
        if (argument == null) {
            return "null";
        }
        if (argument instanceof String) {
            return JQuickStringUtil.abbreviateValue((String) argument, maxValueLength);
        }
        if (argument instanceof Number || argument instanceof Boolean || argument instanceof Character) {
            return String.valueOf(argument);
        }
        if (argument instanceof Throwable) {
            // The type alone is useless here; the message is what identifies the failure.
            Throwable throwable = (Throwable) argument;
            return throwable.getClass().getSimpleName()
                    + (throwable.getMessage() == null ? "" : ": " + throwable.getMessage());
        }
        return argument.getClass().getSimpleName();
    }

    /**
     * Renders a result value, or {@code null} when the capability did not ask for one.
     */
    public static String result(Object value) {
        return value == null ? null : value(value, MAX_VALUE_LENGTH);
    }

    /**
     * Renders the frames of a stack trace, most recent first.
     *
     * @return an immutable list; empty when the throwable carries no stack
     */
    public static List<String> stackTrace(Throwable error) {
        return stackTrace(error, MAX_STACK_FRAMES);
    }

    /**
     * Renders at most {@code maxFrames} frames of a stack trace.
     */
    public static List<String> stackTrace(Throwable error, int maxFrames) {
        if (error == null || maxFrames <= 0) {
            return Collections.emptyList();
        }
        StackTraceElement[] frames = error.getStackTrace();
        if (frames == null || frames.length == 0) {
            return Collections.emptyList();
        }
        List<String> rendered = new ArrayList<String>(Math.min(frames.length, maxFrames));
        for (int i = 0; i < frames.length && i < maxFrames; i++) {
            rendered.add("at " + frames[i]);
        }
        if (frames.length > maxFrames) {
            rendered.add("... " + (frames.length - maxFrames) + " more frame(s)");
        }
        return Collections.unmodifiableList(rendered);
    }

    /**
     * First frame that does not belong to the JDK, i.e. the application code that failed.
     *
     * <p>Used when only one line fits: for an uncaught exception, the top of the stack is almost always
     * inside the JDK's thread machinery, and the useful frame is the first one the application owns.
     */
    public static String firstApplicationFrame(Throwable error) {
        if (error == null) {
            return "(no throwable)";
        }
        StackTraceElement[] frames = error.getStackTrace();
        if (frames == null || frames.length == 0) {
            return "(no stack)";
        }
        for (StackTraceElement frame : frames) {
            String className = frame.getClassName();
            if (!className.startsWith("java.") && !className.startsWith("javax.")
                    && !className.startsWith("sun.") && !className.startsWith("jdk.")) {
                return frame.toString();
            }
        }
        return frames[0].toString();
    }
}
