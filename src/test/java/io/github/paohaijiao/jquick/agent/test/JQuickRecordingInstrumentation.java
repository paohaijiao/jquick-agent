package io.github.paohaijiao.jquick.agent.test;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarFile;

/**
 * A hand-written {@link Instrumentation} that records what the agent asked the JVM to do.
 *
 * <p>The Java agent contract is the one part of the framework that cannot be exercised by calling it:
 * {@code premain} is invoked by the JVM, and the service it receives is provided by the JVM. Without a
 * stand-in, the two claims that matter most about the install sequence — that exactly <em>one</em>
 * transformer is registered, and that the retransformation policy follows the launch mode — would be
 * asserted by reading the source rather than by running it.
 *
 * <p>This stand-in records instead of acting: no class is actually rewritten. That is the correct
 * trade for a unit test, because the byte-code side is already covered end to end by
 * {@link JQuickHarness}, which runs the real enhancer chain.
 *
 * @author Martin
 */
public final class JQuickRecordingInstrumentation implements Instrumentation {

    private final List<ClassFileTransformer> transformers =
            new ArrayList<ClassFileTransformer>();

    private final List<Boolean> retransformFlags = new ArrayList<Boolean>();

    private final List<Class<?>[]> retransformBatches = new ArrayList<Class<?>[]>();

    private final List<ClassDefinition> redefinitions = new ArrayList<ClassDefinition>();

    private final boolean retransformSupported;

    private final boolean redefineSupported;

    private Class<?>[] loadedClasses = new Class<?>[0];

    public JQuickRecordingInstrumentation(boolean retransformSupported, boolean redefineSupported) {
        this.retransformSupported = retransformSupported;
        this.redefineSupported = redefineSupported;
    }

    /* ================================================================== observations */

    /**
     * The transformers the agent registered, in registration order.
     */
    public List<ClassFileTransformer> getTransformers() {
        return Collections.unmodifiableList(transformers);
    }

    /**
     * The {@code canRetransform} argument of each registration, positionally aligned with
     * {@link #getTransformers()}.
     */
    public List<Boolean> getRetransformFlags() {
        return Collections.unmodifiableList(retransformFlags);
    }

    /**
     * Every batch handed to {@code retransformClasses}.
     */
    public List<Class<?>[]> getRetransformBatches() {
        return Collections.unmodifiableList(retransformBatches);
    }

    public List<ClassDefinition> getRedefinitions() {
        return Collections.unmodifiableList(redefinitions);
    }

    /**
     * The classes the fake JVM pretends are loaded; the retransformation pass walks this array.
     */
    public JQuickRecordingInstrumentation withLoadedClasses(Class<?>... classes) {
        this.loadedClasses = classes == null ? new Class<?>[0] : classes;
        return this;
    }

    public int retransformCallCount() {
        int count = 0;
        for (Class<?>[] batch : retransformBatches) {
            count += batch.length;
        }
        return count;
    }

    /* ================================================================== Instrumentation */

    @Override
    public void addTransformer(ClassFileTransformer transformer, boolean canRetransform) {
        transformers.add(transformer);
        retransformFlags.add(Boolean.valueOf(canRetransform));
    }

    @Override
    public void addTransformer(ClassFileTransformer transformer) {
        addTransformer(transformer, false);
    }

    @Override
    public boolean removeTransformer(ClassFileTransformer transformer) {
        int index = transformers.indexOf(transformer);
        if (index < 0) {
            return false;
        }
        transformers.remove(index);
        retransformFlags.remove(index);
        return true;
    }

    @Override
    public boolean isRetransformClassesSupported() {
        return retransformSupported;
    }

    @Override
    public void retransformClasses(Class<?>... classes) throws UnmodifiableClassException {
        retransformBatches.add(classes);
    }

    @Override
    public boolean isRedefineClassesSupported() {
        return redefineSupported;
    }

    @Override
    public void redefineClasses(ClassDefinition... definitions) {
        Collections.addAll(redefinitions, definitions);
    }

    @Override
    public boolean isModifiableClass(Class<?> theClass) {
        return true;
    }

    @Override
    public Class<?>[] getAllLoadedClasses() {
        return loadedClasses;
    }

    @Override
    public Class<?>[] getInitiatedClasses(ClassLoader loader) {
        return loadedClasses;
    }

    @Override
    public long getObjectSize(Object objectToSize) {
        return 0L;
    }

    @Override
    public void appendToBootstrapClassLoaderSearch(JarFile jarfile) {
        // The agent never calls this; recording it would only add noise.
    }

    @Override
    public void appendToSystemClassLoaderSearch(JarFile jarfile) {
        // Same reasoning as the bootstrap search.
    }

    @Override
    public boolean isNativeMethodPrefixSupported() {
        return false;
    }

    @Override
    public void setNativeMethodPrefix(ClassFileTransformer transformer, String prefix) {
        // Not supported, and never requested by this agent.
    }
}
