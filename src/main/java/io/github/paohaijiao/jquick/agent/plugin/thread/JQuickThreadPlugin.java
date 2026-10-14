package io.github.paohaijiao.jquick.agent.plugin.thread;

import io.github.paohaijiao.jquick.agent.core.*;
import io.github.paohaijiao.jquick.agent.support.JQuickLogger;
import io.github.paohaijiao.jquick.agent.trace.JQuickSpanType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Capability: keep the trace context across a thread pool, and stop losing uncaught exceptions.
 *
 * <h3>Context propagation</h3>
 * The classic failure of every thread-local based tracer: the id is set on the request thread, the
 * work is submitted to a pool, and the worker has no id — so the asynchronous half of the request is
 * either untraced or, worse, attributed to whichever request previously used that worker. The fix is
 * to wrap the submitted task at the <b>submission</b> point, which is what this plugin does by
 * instrumenting {@code ThreadPoolExecutor.execute}.
 *
 * <p>That class is a JDK class, so the plugin declares it in the JDK allow list. On HotSpot
 * {@code ThreadPoolExecutor} <em>is</em> retransformable and this works — it is the same technique
 * SkyWalking uses. The capability is opt-in and the allow list is printed at startup, because
 * instrumenting a class the whole JVM uses is a decision an operator must make knowingly.
 *
 * <h3>Uncaught exceptions</h3>
 * A worker thread that dies from an exception nobody handled is one of the hardest failures to
 * diagnose in production: the submitter often never learns. Installing a default handler turns it
 * into a recorded event. The previously installed handler — usually the framework's own — is
 * preserved and still runs, so this is additive.
 *
 * @author Martin
 */
public final class JQuickThreadPlugin implements JQuickPlugin {

    public static final String NAME = "thread";

    private static final List<String> DEFAULT_POOL_TARGETS = Arrays.asList(
            "java.util.concurrent.ThreadPoolExecutor",
            "java.util.concurrent.ScheduledThreadPoolExecutor",
            "java.util.concurrent.ForkJoinPool");

    private final List<JQuickClassEnhancer> enhancers = new ArrayList<JQuickClassEnhancer>();

    /**
     * Full thread inventory with the top frames of each thread, for a leak or a deadlock hunt.
     *
     * @param maxFramesPerThread how many frames to print per thread
     */
    public static String census(int maxFramesPerThread) {
        return JQuickThreadMonitor.report(maxFramesPerThread);
    }

    /**
     * Entry point for the two-sample leak check: take one {@link JQuickThreadMonitor#snapshot()}
     * early, another later, and ask which roles grew beyond the expected count.
     */
    public static java.util.List<String> findLeaks(java.util.Map<String, Integer> baseline,
                                                   int maxExpected) {
        return JQuickThreadMonitor.findLeaks(baseline, maxExpected);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public JQuickCategory category() {
        return JQuickCategory.TRACING;
    }

    @Override
    public boolean isEnabled(JQuickConfig config) {
        return config.getBoolean(JQuickConfig.THREAD_ENABLED, false);
    }

    @Override
    public void initialize(JQuickEnvironment environment) {
        JQuickConfig config = environment.getConfig();
        if (config.getBoolean(JQuickConfig.THREAD_POOL_ENABLED, true)) {
            installPoolPropagation(environment, config);
        }
        if (config.getBoolean(JQuickConfig.THREAD_UNCAUGHT_ENABLED, true)) {
            installUncaughtExceptionHandler(environment);
        }
        installThreadCensus(environment);
    }

    /**
     * Exposes the live thread count, so a leak is a rising line on a dashboard rather than something
     * someone notices after the JVM refuses to create a thread.
     *
     * <p>The full inventory is not collected here: {@link JQuickThreadMonitor} walks every thread in
     * the JVM, which has no business happening during agent installation. What is registered is the
     * cheap counter; the inventory stays on demand, and {@link #census()} is the entry point for an
     * admin endpoint.
     */
    private void installThreadCensus(JQuickEnvironment environment) {
        try {
            environment.getMeters().gauge("jquick_threads",
                    new io.github.paohaijiao.jquick.agent.metrics.JQuickGauge.ValueSource() {
                        @Override
                        public double read() {
                            return JQuickThreadMonitor.count();
                        }
                    });
            JQuickLogger.debug("thread census: " + JQuickThreadMonitor.topRoles(5));
        } catch (Throwable ex) {
            JQuickLogger.suppressed("thread census gauge failed", ex);
        }
    }

    private void installPoolPropagation(JQuickEnvironment environment, JQuickConfig config) {
        List<String> targets = config.getList("thread.pool.includes");
        if (targets.isEmpty()) {
            targets = DEFAULT_POOL_TARGETS;
        }
        environment.allowJdk(targets);
        environment.setThreadPoolWrapping(true);
        enhancers.add(new JQuickInstrumentEnhancer(
                "thread-pool",
                JQuickClassMatcher.byName(targets.toArray(new String[0])),
                JQuickMethodMatcher.named("execute", "submit", "schedule", "invoke", "invokeAll"),
                JQuickSpanType.LOCAL,
                "thread-pool",
                environment));
        JQuickLogger.info("thread pool context propagation enabled for " + targets
                + " (declared in the JDK allow list)");
    }

    private void installUncaughtExceptionHandler(JQuickEnvironment environment) {
        try {
            Thread.UncaughtExceptionHandler previous =
                    Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(
                    new JQuickUncaughtExceptionHandler(previous, environment));
            JQuickLogger.info("uncaught exception handler installed (previous="
                    + (previous == null ? "none" : previous.getClass().getName()) + ")");
        } catch (Throwable ex) {
            JQuickLogger.suppressed("failed to install the uncaught exception handler", ex);
        }
    }

    @Override
    public List<JQuickClassEnhancer> enhancers() {
        return enhancers;
    }

    @Override
    public String description() {
        return "thread pool context propagation, uncaught exception capture, thread census";
    }
}
