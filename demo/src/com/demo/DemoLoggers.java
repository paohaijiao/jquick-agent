package com.demo;

/**
 * Holds the application's logger, the way a real application obtains one from a logging framework.
 *
 * <p>The application only ever sees {@link DemoLogger}; which implementation is behind it is an
 * internal detail. That indirection is what makes the agent's log capability usable in practice: one
 * rule in {@code log.includes} covers every logger in the process, because every logger implements the
 * same interface.
 *
 * @author Martin
 */
public final class DemoLoggers {

    /**
     * The application logger.
     */
    public static final DemoLogger APP = new ConsoleLogger();

    private DemoLoggers() {
    }
}
