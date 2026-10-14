package io.github.paohaijiao.jquick.agent.core;

/**
 * Byte-code enhancement extension point.
 *
 * <p>An enhancer is the smallest unit of instrumentation: given a class it either declines
 * ({@link #supports}) or returns replacement bytes. Every capability of the agent — tracing, JDBC,
 * RASP, mocking, coverage, … — is expressed as one or more enhancers, which is what makes the
 * transformer generic and the capabilities independent of each other.
 *
 * <p><b>Implementation rule:</b> the actual byte-code editing must be delegated to <b>jquick-asm</b>
 * ({@code JQuickMethodEnhancer} / {@code JQuickBaseMethodVisitor}); an enhancer must never
 * hand-write an {@code org.objectweb.asm.ClassVisitor} or manipulate the constant pool itself.
 *
 * <p><b>Failure rule:</b> {@code enhance} must not throw. The transformer wraps the call, but an
 * enhancer that returns {@code null} instead of failing is significantly cheaper to diagnose.
 *
 * @author Martin
 */
public interface JQuickClassEnhancer {

    /**
     * Stable capability name used in diagnostics, e.g. {@code method-trace} or {@code jdbc}.
     */
    String name();

    /**
     * Cheap pre-check: is this class interesting at all?
     *
     * <p>Called before {@link #enhance(JQuickClassContext)} so that a class is parsed once rather
     * than once per enhancer.
     */
    boolean supports(JQuickClassContext context);

    /**
     * Applies the enhancement.
     *
     * @param context the class and its metadata
     * @return replacement bytes, or {@code null} when the class is left untouched
     */
    byte[] enhance(JQuickClassContext context);
}
