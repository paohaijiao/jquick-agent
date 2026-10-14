package io.github.paohaijiao.jquick.agent.runtime;

import io.github.paohaijiao.jquick.agent.trace.JQuickTraceContext;
import io.github.paohaijiao.jquick.agent.trace.JQuickTraceSnapshot;

/**
 * Carries a captured trace context across a thread-pool hand-off.
 *
 * <p>A {@link ThreadLocal} is not inherited by a pool worker, so an asynchronous task would
 * otherwise start a brand new trace — or worse, inherit the trace of whichever task previously used
 * the same worker. This wrapper is installed at submission time by
 * {@link JQuickSink#wrapRunnable(Runnable)} and does three things around the real task:
 *
 * <ol>
 *   <li>installs the captured context, including the whole span stack, so the task's spans attach to
 *       the correct parent;</li>
 *   <li>runs the task;</li>
 *   <li>clears the thread state in a {@code finally} block, so the worker does not leak a finished
 *       trace into the next task it picks up.</li>
 * </ol>
 *
 * <p>Implementing {@link Runnable} rather than extending a framework type keeps it usable with any
 * executor; {@link JQuickSink#wrapRunnable(Runnable)} is idempotent, so a task already wrapped is
 * never wrapped twice.
 *
 * @author Martin
 */
public final class JQuickTraceAwareRunnable implements Runnable {

    private final Runnable delegate;

    private final JQuickTraceSnapshot snapshot;

    public JQuickTraceAwareRunnable(Runnable delegate, JQuickTraceSnapshot snapshot) {
        this.delegate = delegate;
        this.snapshot = snapshot;
    }

    @Override
    public void run() {
        JQuickTraceSnapshot previous = JQuickTraceContext.capture();
        try {
            JQuickTraceContext.restore(snapshot);
            delegate.run();
        } finally {
            // Restore whatever the worker had before this task, which also clears the state when the
            // worker was idle: a pooled thread must never retain a finished trace.
            JQuickTraceContext.restore(previous);
        }
    }

    /**
     * The wrapped task.
     */
    public Runnable getDelegate() {
        return delegate;
    }

    @Override
    public String toString() {
        return "JQuickTraceAwareRunnable{" + delegate + '}';
    }
}
