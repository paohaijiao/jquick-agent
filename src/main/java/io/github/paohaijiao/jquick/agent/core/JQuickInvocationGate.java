package io.github.paohaijiao.jquick.agent.core;

/**
 * Interception extension point: inspects a call before the method body runs and may refuse it.
 *
 * <p>Where a {@link JQuickMethodListener} <em>observes</em>, a gate <em>decides</em>. The separation
 * is deliberate: observation must never affect the application, enforcement always does, and mixing
 * the two in one interface makes it impossible to reason about which plugin can change behaviour.
 *
 * <p>Gates are consulted in registration order and the first rejection wins.
 *
 * @author Martin
 */
public interface JQuickInvocationGate {

    /**
     * Inspects the invocation.
     *
     * @param invocation the in-flight call, with arguments already captured when the plan required it
     * @throws JQuickRejectedException to refuse the call
     */
    void inspect(JQuickInvocation invocation) throws JQuickRejectedException;

    /**
     * Whether this gate needs the arguments to be captured.
     */
    default boolean requiresArguments() {
        return true;
    }

    /**
     * Human readable identifier used in diagnostics.
     */
    default String name() {
        return getClass().getSimpleName();
    }
}
