package io.github.paohaijiao.jquick.agent.core;

/**
 * Interception extension point: replaces the arguments of a call before the body runs.
 *
 * <p>Transforming an argument is more invasive than observing it — the emitter has to write the
 * possibly-replaced array back into the local variables of the instrumented method — so it is a
 * distinct interface and an opt-in capability.
 *
 * <p>Typical uses: transparent decryption of an inbound argument, rewriting a SQL string for a
 * multi-tenant data source, normalising a tenant id, forcing a trace id onto a downstream call.
 *
 * <p><b>Contract for implementations.</b> The returned array must
 * <ul>
 *   <li>be non-null,</li>
 *   <li>have the same length as the input,</li>
 *   <li>hold, at every position, either a value assignable to the declared parameter type or the
 *       original value. A {@code null} at the position of a primitive parameter produces an
 *       {@code NPE} in the instrumented method.</li>
 * </ul>
 * The runtime sink validates the first two conditions and falls back to the original array, but the
 * third cannot be checked without knowing the descriptor, and is therefore the implementation's
 * responsibility.
 *
 * @author Martin
 */
public interface JQuickArgumentTransformer {

    /**
     * Returns the arguments the method body should run with.
     *
     * @param invocation the in-flight call
     * @param arguments  the captured arguments, never {@code null}
     * @return the array to use; returning {@code arguments} unchanged is a valid no-op
     */
    Object[] transform(JQuickInvocation invocation, Object[] arguments);

    /**
     * Human readable identifier used in diagnostics.
     */
    default String name() {
        return getClass().getSimpleName();
    }
}
