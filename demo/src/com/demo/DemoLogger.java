package com.demo;

/**
 * The demo's logging facade.
 *
 * <p>It exists so the demo can show the agent's log capability without dragging a logging framework
 * into the sample, and structurally it is exactly what {@code org.slf4j.Logger} is: an interface the
 * application calls, plus an implementation that does the writing.
 *
 * <p>That shape is what the agent keys on. It does not look for a class called {@code Logger}; it looks
 * for <b>implementations of</b> the interfaces named in {@code log.includes}. The concrete class
 * therefore only has to implement this interface to be found, which is how the same mechanism covers
 * SLF4J, Log4j 1, Log4j 2 and {@code java.util.logging} — in each case the application calls an
 * interface (or a directly implemented class) and one implementation per backend does the work.
 *
 * <p>Note what this file does <em>not</em> contain: any reference to the agent, any configuration, and
 * any hook of its own. The instrumented class is {@link ConsoleLogger}, which appears nowhere in the
 * agent arguments.
 *
 * @author Martin
 */
public interface DemoLogger {

    /**
     * Writes an informational line.
     *
     * @param message the message; the agent prefixes it with the current trace id when one is active
     */
    void info(String message);

    /**
     * Writes a warning line.
     *
     * <p>A second method with a different descriptor, so the demo also shows that the rewrite applies
     * per call rather than per class.
     *
     * @param message the message
     * @param error   the accompanying failure, or {@code null}
     */
    void warn(String message, Throwable error);
}
