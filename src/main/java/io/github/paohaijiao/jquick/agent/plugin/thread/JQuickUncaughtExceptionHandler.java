package io.github.paohaijiao.jquick.agent.plugin.thread;

import io.github.paohaijiao.jquick.agent.core.JQuickEnvironment;
import io.github.paohaijiao.jquick.agent.support.JQuickLogger;
import io.github.paohaijiao.jquick.agent.support.JQuickValueFormatter;

/**
 * Captures exceptions that killed a thread without being handled anywhere.
 *
 * <p>Three things happen for every such exception:
 * <ol>
 *   <li>the previously installed default handler still runs, so a framework that logs these itself
 *       keeps working — this handler is additive, never a replacement;</li>
 *   <li>a counter is incremented, which is what makes the failure rate of an asynchronous path
 *       visible on a dashboard for the first time;</li>
 *   <li>a diagnostic line including the thread name and the top frame is written, which is usually
 *       enough to identify the pool and the task without a heap dump.</li>
 * </ol>
 *
 * @author Martin
 */
public final class JQuickUncaughtExceptionHandler implements Thread.UncaughtExceptionHandler {

    private final Thread.UncaughtExceptionHandler previous;

    private final JQuickEnvironment environment;

    public JQuickUncaughtExceptionHandler(Thread.UncaughtExceptionHandler previous,
                                          JQuickEnvironment environment) {
        this.previous = previous;
        this.environment = environment;
    }

    @Override
    public void uncaughtException(Thread thread, Throwable error) {
        try {
            String threadName = thread == null ? "(unknown)" : thread.getName();
            String location = JQuickValueFormatter.firstApplicationFrame(error);
            JQuickLogger.error("uncaught exception on thread '" + threadName + "' at " + location,
                    error);
            if (environment != null && environment.getMeters() != null) {
                environment.getMeters()
                        .counter("jquick_uncaught_exceptions_total", "thread", threadName)
                        .increment();
            }
        } catch (Throwable ignored) {
            // Diagnostics must never affect the dying thread's exit path.
        } finally {
            if (previous != null && previous != this) {
                try {
                    previous.uncaughtException(thread, error);
                } catch (Throwable ignored) {
                    // A broken previous handler is not our problem.
                }
            }
        }
    }

    /**
     * The handler that was in place before the agent installed itself.
     */
    public Thread.UncaughtExceptionHandler getPrevious() {
        return previous;
    }
}
