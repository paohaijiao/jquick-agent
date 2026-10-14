package io.github.paohaijiao.jquick.agent.bootstrap;

import io.github.paohaijiao.jquick.agent.core.JQuickEnvironment;
import io.github.paohaijiao.jquick.agent.support.JQuickLogger;

import java.lang.instrument.Instrumentation;

/**
 * The agent entry points named in the jar manifest.
 *
 * <p>There are exactly two, and they do exactly the same thing: {@link #premain} is called by the JVM
 * when the jar is named with {@code -javaagent}, {@link #agentmain} when the jar is loaded into an
 * already-running JVM through the Attach API. Both delegate to {@link JQuickBootstrap}, which is what
 * keeps the two modes from drifting apart — a capability that works on startup and not on attach is
 * one of the most common and most annoying agent bugs, and it is always caused by the two paths having
 * separate implementations.
 *
 * <h3>Why these methods are so thin</h3>
 * Two reasons, and both are about the failure mode.
 *
 * <ul>
 *   <li>An exception thrown from {@code premain} terminates the JVM before {@code main} runs, with a
 *       stack trace that points at the agent and no clue about the application. An exception from
 *       {@code agentmain} is worse: it propagates into the attaching tool. Both are caught here, and
 *       both are reported on {@code System.err} with the app's own output left clean.</li>
 *   <li>A signature mistake in the manifest ({@code Premain-Class} pointing at the wrong class, a
 *       method that is not {@code public static void (String, Instrumentation)}) produces a JVM error
 *       that mentions none of the relevant facts. Keeping the bodies at one statement each makes it
 *       obvious by inspection that the contract is satisfied.</li>
 * </ul>
 *
 * @author Martin
 */
public final class JQuickAgent {

    private JQuickAgent() {
    }

    /**
     * JVM entry point for {@code -javaagent:jquick-agent.jar=agentArgs}.
     *
     * <p>Runs before the application's {@code main}, on the main thread, with the application not yet
     * started. Instrumentation therefore only affects classes loaded from this point on —
     * {@code java.lang.*} and anything the JVM loaded during its own bootstrap are already in place and
     * cannot be retransformed anyway.
     */
    public static void premain(String agentArgs, Instrumentation instrumentation) {
        try {
            JQuickBootstrap.install(agentArgs, instrumentation, true);
        } catch (Throwable ex) {
            // install() already guards itself; this is a belt-and-braces net around argument parsing
            // and class loading, which happen before it.
            JQuickLogger.error("premain failed; continuing without the agent", ex);
        }
    }

    /**
     * JVM entry point for a dynamic attach.
     *
     * <p>Runs inside the target JVM on a thread created by the Attach API, with the application fully
     * started. Classes are already loaded, so the bootstrap performs a retransformation pass by
     * default — without it, attaching would report success and instrument nothing.
     */
    public static void agentmain(String agentArgs, Instrumentation instrumentation) {
        try {
            JQuickBootstrap.install(agentArgs, instrumentation, false);
        } catch (Throwable ex) {
            JQuickLogger.error("agentmain failed; the target JVM is left untouched", ex);
        }
    }


    /**
     * Installs the agent without the JVM's agent hook.
     *
     * <p>Needed in three situations, all of them legitimate: a unit test that wants the runtime and
     * the emitter without an {@link Instrumentation}; a container or framework that already owns the
     * agent lifecycle; and the demos, which must work when the jar is on the classpath rather than on
     * the command line.
     *
     * @param agentArgs       the same {@code key=value;...} string {@code premain} receives
     * @param instrumentation the service if one is available, otherwise {@code null}
     */
    public static void install(String agentArgs, Instrumentation instrumentation) {
        try {
            JQuickBootstrap.install(agentArgs, instrumentation, instrumentation == null);
        } catch (Throwable ex) {
            JQuickLogger.error("programmatic install failed", ex);
        }
    }

    /**
     * Whether the agent has been installed, whichever route was taken.
     */
    public static boolean isInstalled() {
        return JQuickEnvironment.isInstalled();
    }
}
