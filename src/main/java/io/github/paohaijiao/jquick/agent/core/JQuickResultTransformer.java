package io.github.paohaijiao.jquick.agent.core;

/**
 * Interception extension point: replaces the return value of a call.
 *
 * <p>Used by the data-protection capabilities — result desensitisation (mask a phone number, an
 * identity card, an e-mail) and transparent encryption of an outbound payload — and by the
 * error-handling policies that degrade a failure into a fallback value.
 *
 * <p><b>Contract for implementations.</b> Return the value the caller must observe:
 * <ul>
 *   <li>to keep the original result, return {@code result} itself;</li>
 *   <li>to replace it, return a value assignable to the declared return type;</li>
 *   <li>for a {@code void} method the incoming {@code result} is {@code null} and the returned
 *       value is discarded, so implementations returned for void methods should simply return
 *       {@code null}.</li>
 * </ul>
 *
 * @author Martin
 */
public interface JQuickResultTransformer {

    /**
     * Returns the value the caller should observe.
     *
     * @param invocation the in-flight call
     * @param result     the original return value, {@code null} for {@code void} and for methods that
     *                   returned {@code null}
     */
    Object transform(JQuickInvocation invocation, Object result);

    /**
     * Whether the transformer also wants to see failed calls (where {@code result} is {@code null}).
     * Used by fallback policies.
     */
    default boolean handlesErrors() {
        return false;
    }

    /**
     * Human readable identifier used in diagnostics.
     */
    default String name() {
        return getClass().getSimpleName();
    }
}
