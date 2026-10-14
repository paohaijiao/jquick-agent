package io.github.paohaijiao.jquick.agent.support;

/**
 * Minimal diagnostic logger for the agent itself.
 *
 * <p>The agent must never depend on the logging framework of the target application: doing so would
 * create a class-loading cycle (the agent is loaded before the application logging stack, and its
 * own classes are excluded from instrumentation). A tiny {@code System.err} writer keeps the agent
 * dependency-free while remaining easy to grep in a container log stream.
 *
 * <p>Framework diagnostics go to {@code System.err} on purpose; trace/audit payloads produced by
 * the plugins go to {@code System.out} (or to a user supplied sink), so the two streams can be
 * routed independently by the operator.
 *
 * @author Martin
 */
public final class JQuickLogger {

    /**
     * Tag prefixed to every agent diagnostic line.
     */
    public static final String TAG = "[jquick-agent]";

    private static volatile boolean quiet = false;

    private static volatile boolean debug = false;

    private JQuickLogger() {
    }

    public static boolean isQuiet() {
        return quiet;
    }

    /**
     * Silences every diagnostic (errors included). Useful in latency sensitive benchmarks.
     */
    public static void setQuiet(boolean value) {
        quiet = value;
    }

    public static boolean isDebug() {
        return debug;
    }

    public static void setDebug(boolean value) {
        debug = value;
    }

    public static void debug(String message) {
        if (debug && !quiet) {
            write("DEBUG", message);
        }
    }

    public static void info(String message) {
        if (!quiet) {
            write("INFO", message);
        }
    }

    public static void warn(String message) {
        if (!quiet) {
            write("WARN", message);
        }
    }

    public static void error(String message, Throwable cause) {
        if (!quiet) {
            write("ERROR", message + describe(cause));
        }
    }

    /**
     * Reports an internal failure that was contained. Never throws, never allocates when quiet.
     *
     * <p>Every framework extension point funnels its failures through this method so that a broken
     * plugin can never break the host application.
     */
    public static void suppressed(String message, Throwable cause) {
        if (!quiet) {
            write("WARN", message + describe(cause));
        }
    }

    private static String describe(Throwable cause) {
        if (cause == null) {
            return "";
        }
        String text = cause.toString();
        return text.isEmpty() ? " -> " + cause.getClass().getName() : " -> " + text;
    }

    private static void write(String level, String message) {
        try {
            System.err.println(TAG + " [" + level + "] " + message);
        } catch (Throwable ignored) {
            // Printing must never be able to break the target application.
        }
    }
}
