package io.github.paohaijiao.jquick.agent.core;

import io.github.paohaijiao.jquick.agent.support.JQuickLogger;

import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.util.ArrayList;
import java.util.List;

/**
 * Thin, safe wrapper around the JVM's {@link Instrumentation} service.
 *
 * <p>Several capabilities need the raw service — retransformation during a dynamic attach, class
 * redefinition for the hot-fix API, the loaded-class inventory for the class-loading report — and
 * they all need it defensively: {@code retransformClasses} throws for classes that cannot be
 * rewritten, and one such class must never abort a batch. Funnelling every call through this class
 * is what keeps that policy in one place.
 *
 * @author Martin
 */
public final class JQuickInstrumentation {

    private static volatile Instrumentation instrumentation;

    private JQuickInstrumentation() {
    }

    /**
     * Binds the JVM service; called by the bootstrap.
     */
    public static void bind(Instrumentation value) {
        instrumentation = value;
    }

    public static Instrumentation get() {
        return instrumentation;
    }

    public static boolean isAvailable() {
        return instrumentation != null;
    }

    /**
     * Whether the JVM allows class redefinition ({@code Can-Redefine-Classes} in the manifest).
     */
    public static boolean canRedefine() {
        Instrumentation current = instrumentation;
        return current != null && current.isRedefineClassesSupported();
    }

    /**
     * Whether the JVM allows retransformation.
     */
    public static boolean canRetransform() {
        Instrumentation current = instrumentation;
        return current != null && current.isRetransformClassesSupported();
    }

    /**
     * Whether the JVM allows the native-method prefix to be set.
     */
    public static boolean canSetNativeMethodPrefix() {
        Instrumentation current = instrumentation;
        return current != null && current.isNativeMethodPrefixSupported();
    }

    /**
     * Redefines a class from replacement bytes.
     *
     * <p>Redefinition is stricter than retransformation: it is only allowed to change method bodies,
     * never the class structure (no field, no method, no superclass change). That restriction is
     * exactly the boundary the hot-fix capability advertises, so it is enforced by the JVM rather
     * than by our own bookkeeping.
     *
     * @return {@code true} when the class was redefined
     */
    public static boolean redefine(Class<?> target, byte[] classBytes) {
        Instrumentation current = instrumentation;
        if (current == null || target == null || classBytes == null || classBytes.length == 0) {
            return false;
        }
        try {
            current.redefineClasses(
                    new java.lang.instrument.ClassDefinition(target, classBytes));
            return true;
        } catch (Throwable ex) {
            JQuickLogger.error("redefineClasses failed for " + target.getName(), ex);
            return false;
        }
    }

    /**
     * Retransforms the given classes, tolerating the ones the JVM refuses.
     *
     * @return number of classes actually retransformed
     */
    public static int retransform(Class<?>... targets) {
        Instrumentation current = instrumentation;
        if (current == null || targets == null || targets.length == 0) {
            return 0;
        }
        int retransformed = 0;
        for (Class<?> target : targets) {
            if (target == null || !current.isModifiableClass(target)) {
                continue;
            }
            try {
                current.retransformClasses(target);
                retransformed++;
            } catch (UnmodifiableClassException ex) {
                JQuickLogger.debug("class not retransformable: " + target.getName());
            } catch (Throwable ex) {
                JQuickLogger.suppressed("retransformation failed for " + target.getName(), ex);
            }
        }
        return retransformed;
    }

    /**
     * Retransforms every currently loaded class matching the predicate, in batches, tolerating
     * failures.
     *
     * @return number of classes actually retransformed
     */
    public static int retransformMatching(JQuickClassPredicate predicate) {
        Instrumentation current = instrumentation;
        if (current == null || predicate == null) {
            return 0;
        }
        Class<?>[] loaded = current.getAllLoadedClasses();
        if (loaded == null) {
            return 0;
        }
        List<Class<?>> targets = new ArrayList<Class<?>>();
        for (Class<?> candidate : loaded) {
            if (candidate == null || candidate.isArray() || candidate.isPrimitive()) {
                continue;
            }
            try {
                if (!predicate.test(candidate)) {
                    continue;
                }
            } catch (Throwable ignored) {
                continue;
            }
            if (current.isModifiableClass(candidate)) {
                targets.add(candidate);
            }
        }
        return retransform(targets.toArray(new Class<?>[targets.size()]));
    }

    public static int loadedClassCount() {
        Instrumentation current = instrumentation;
        if (current == null) {
            return -1;
        }
        return current.getAllLoadedClasses().length;
    }

    /**
     * Predicate over loaded classes; declared here to keep this class free of functional-package
     * imports for the reader's sake.
     */
    public interface JQuickClassPredicate {

        boolean test(Class<?> candidate);
    }
}
