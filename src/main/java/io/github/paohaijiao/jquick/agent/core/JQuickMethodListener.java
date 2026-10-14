package io.github.paohaijiao.jquick.agent.core;

/**
 * Runtime extension point: observes every instrumented call.
 *
 * <p>Listeners are the only way a capability observes calls, which keeps the instrumentation itself
 * generic. The default methods are not decoration — they are the <b>contract between the plugin and
 * the byte-code emitter</b>: the emitter asks the environment which of these flags any listener
 * needs, and only then injects the (more expensive) argument-array construction or result boxing.
 * A listener that observes spans only therefore costs a single string constant per call.
 *
 * <p>Implementations must be thread safe and must never throw: the sink already wraps each dispatch
 * in a try/catch, but a defensive implementation keeps the host application responsive.
 *
 * @author Martin
 */
@FunctionalInterface
public interface JQuickMethodListener {

    /**
     * Called for every event of every instrumented call.
     *
     * <p>Domains that only care about finished spans should return early for
     * {@link JQuickMethodEvent.JQuickPhase#ENTER}.
     */
    void onMethodEvent(JQuickMethodEvent event);

    /**
     * Whether the listener needs the captured arguments on
     * {@link JQuickMethodEvent.JQuickPhase#ENTER}.
     */
    default boolean captureArguments() {
        return false;
    }

    /**
     * Whether the listener may <em>replace</em> the arguments, which forces the emitter to write the
     * array back into the local variables of the instrumented method.
     */
    default boolean transformArguments() {
        return false;
    }

    /**
     * Whether the listener needs the return value.
     */
    default boolean captureResult() {
        return false;
    }

    /**
     * Whether the listener may <em>replace</em> the return value.
     */
    default boolean transformResult() {
        return false;
    }

    /**
     * Human readable identifier used in diagnostics.
     */
    default String name() {
        return getClass().getSimpleName();
    }
}
