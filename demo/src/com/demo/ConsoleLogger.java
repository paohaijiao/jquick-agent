package com.demo;

import java.io.PrintStream;

/**
 * The logging implementation the agent instruments.
 *
 * <p>Nothing in the agent configuration names this class. The agent selects it because it is a
 * <em>subtype</em> of {@link DemoLogger}, which is named in {@code log.includes} — the same
 * hierarchy-based selection that lets one rule cover every SLF4J binding in an application.
 *
 * <p>The injected work happens in {@link #info(String)} and {@link #warn(String, Throwable)}: the
 * first {@code String} argument is rewritten to carry the current trace id (and, when
 * {@code log.mask.enabled=true}, to have its sensitive values masked) before the real body runs. The
 * body below is unchanged — see for yourself, there is no agent code in it.
 *
 * @author Martin
 */
public final class ConsoleLogger implements DemoLogger {

    private final PrintStream out = System.out;

    @Override
    public void info(String message) {
        out.println("[" + Thread.currentThread().getName() + "] INFO  " + message);
    }

    @Override
    public void warn(String message, Throwable error) {
        out.println("[" + Thread.currentThread().getName() + "] WARN  " + message
                + (error == null ? "" : " (" + error.getClass().getSimpleName()
                        + ": " + error.getMessage() + ")"));
    }
}
