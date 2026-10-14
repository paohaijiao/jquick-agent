package io.github.paohaijiao.jquick.agent.core;

/**
 * Extension point backing the unit-test mocking capability.
 *
 * <p>This is the byte-code level equivalent of a mocking framework: instead of proxying an interface
 * at compile time, the stub is consulted from the entry hook of the <em>real</em> implementation, and
 * when a stub is registered the original body is never executed. It removes the need for
 * {@code PowerMock}/{@code Mockito-inline} style class-loader tricks when an external dependency has
 * to be replaced in an integration test.
 *
 * <p>Semantics of {@link #resolve(String)}:
 * <ul>
 *   <li>{@code null} — the method is not stubbed, run the real body;</li>
 *   <li>{@link #VOID} — the method is stubbed and must return immediately without a value (for a
 *       {@code void} method);</li>
 *   <li>any other value — the method is stubbed and must return that value. It has to be assignable
 *       to the declared return type, otherwise the instrumented method fails with a
 *       {@code ClassCastException}.</li>
 * </ul>
 *
 * @author Martin
 */
public interface JQuickStubResolver {

    /**
     * Sentinel returned by {@link #resolve(String)} to stub a {@code void} method.
     */
    Object VOID = new Object();

    /**
     * Resolves the stubbed value for a method signature.
     *
     * @param signature the readable signature, e.g. {@code com.demo.OrderDao#save(java.lang.String)}
     * @return {@code null}, {@link #VOID}, or the value to return
     */
    Object resolve(String signature);

    /**
     * Human readable identifier used in diagnostics.
     */
    default String name() {
        return getClass().getSimpleName();
    }
}
