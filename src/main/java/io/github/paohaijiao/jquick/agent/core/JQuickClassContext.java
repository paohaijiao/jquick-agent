package io.github.paohaijiao.jquick.agent.core;

/**
 * Everything an enhancer needs to know about one class being instrumented.
 *
 * <p>Bundling the four values into a context object (instead of a four-argument method) means new
 * information can be added later — a retransformation flag, the reason for the current pass — without
 * breaking every existing enhancer.
 *
 * @author Martin
 */
public final class JQuickClassContext {

    private final String className;

    private final ClassLoader loader;

    private final byte[] classBytes;

    private final JQuickClassMeta meta;

    private final boolean retransformation;

    public JQuickClassContext(String className, ClassLoader loader, byte[] classBytes,
                              JQuickClassMeta meta, boolean retransformation) {
        this.className = className;
        this.loader = loader;
        this.classBytes = classBytes;
        this.meta = meta;
        this.retransformation = retransformation;
    }

    /**
     * Binary class name, dot separated, e.g. {@code com.demo.OrderService}.
     */
    public String getClassName() {
        return className;
    }

    /**
     * The defining class loader; {@code null} for bootstrap classes.
     */
    public ClassLoader getLoader() {
        return loader;
    }

    /**
     * Current class bytes, i.e. the output of the previously applied enhancers.
     */
    public byte[] getClassBytes() {
        return classBytes;
    }

    /**
     * Parsed metadata; never {@code null} when {@code supports} is invoked.
     */
    public JQuickClassMeta getMeta() {
        return meta;
    }

    /**
     * Whether this pass was triggered by {@code retransformClasses} rather than by an initial load.
     */
    public boolean isRetransformation() {
        return retransformation;
    }

    /**
     * Returns a context carrying replacement bytes, used to chain enhancers.
     */
    public JQuickClassContext withBytes(byte[] replacement) {
        return new JQuickClassContext(className, loader, replacement,
                JQuickClassMeta.of(replacement), retransformation);
    }

    @Override
    public String toString() {
        return "JQuickClassContext{" + className
                + ", loader=" + (loader == null ? "bootstrap" : loader.toString())
                + ", retransformation=" + retransformation + '}';
    }
}
