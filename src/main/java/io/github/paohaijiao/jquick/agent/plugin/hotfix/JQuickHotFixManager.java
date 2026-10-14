package io.github.paohaijiao.jquick.agent.plugin.hotfix;

import com.jquick.asm.enhance.JQuickMethodAdvice;
import com.jquick.asm.enhance.JQuickMethodContext;
import com.jquick.asm.enhance.JQuickMethodEnhancer;
import io.github.paohaijiao.jquick.agent.core.JQuickInstrumentation;
import io.github.paohaijiao.jquick.agent.support.JQuickBytecodeEmitter;
import io.github.paohaijiao.jquick.agent.support.JQuickDescriptorUtil;
import io.github.paohaijiao.jquick.agent.support.JQuickLogger;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Hot fix: replace a method body in a running JVM, without a restart and without a redeploy.
 *
 * <p>Two entry points, both built on the same primitive — jquick-asm builds the replacement bytes and
 * {@link JQuickInstrumentation#redefine(Class, byte[])} hands them to the JVM:
 *
 * <ul>
 *   <li>{@link #forceReturn(Class, String, String, Object)} — the ByteMan {@code RETURN} rule: make
 *       a method return a constant. This covers the most common emergency of all, "the response is
 *       wrong because <em>this</em> value is wrong", in one call;</li>
 *   <li>{@link #redefine(Class, byte[])} — apply bytes you built yourself, e.g. a patched class
 *       compiled offline.</li>
 * </ul>
 *
 * <h3>The boundary, enforced by the JVM</h3>
 * Redefinition may only change method bodies. Adding or removing a field, adding or removing a
 * method, changing the superclass or the interface list all fail with
 * {@code UnsupportedOperationException}. That is not a limitation of this API — it is the JVM's
 * definition of {@code redefineClasses}, and it is exactly why a hot fix is a stopgap for a bad
 * constant or a bad branch, not a deployment mechanism.
 *
 * <p>Two further practical constraints are worth knowing before an incident rather than during one:
 * a redefinition is process-local (an instance that restarts loses it), and instrumented classes are
 * re-instrumented on the next definition, so a hot fix and the agent compose rather than conflict.
 *
 * @author Martin
 */
public final class JQuickHotFixManager {

    private JQuickHotFixManager() {
    }

    /**
     * Redefines a loaded class from replacement bytes.
     *
     * @return {@code true} when the class was redefined
     */
    public static boolean redefine(Class<?> target, byte[] classBytes) {
        if (target == null || classBytes == null || classBytes.length == 0) {
            return false;
        }
        if (!JQuickInstrumentation.canRedefine()) {
            JQuickLogger.error("redefineClasses is not supported by this JVM or the agent manifest "
                    + "is missing Can-Redefine-Classes: true", null);
            return false;
        }
        boolean redefined = JQuickInstrumentation.redefine(target, classBytes);
        if (redefined) {
            JQuickLogger.info("hot fix applied to " + target.getName());
        }
        return redefined;
    }

    /**
     * Makes a method return a constant.
     *
     * @param target     the loaded class to patch
     * @param methodName method name, e.g. {@code applyDiscount}
     * @param descriptor JVM method descriptor, e.g. {@code (D)D}
     * @param value      the value to return; boxed primitives and {@code String} are converted to the
     *                   declared return type
     * @return {@code true} when the class was redefined
     */
    public static boolean forceReturn(Class<?> target, String methodName, String descriptor,
                                      Object value) {
        if (target == null || methodName == null || descriptor == null) {
            return false;
        }
        try {
            byte[] original = readClassBytes(target);
            if (original == null) {
                JQuickLogger.error("cannot read the bytes of " + target.getName(), null);
                return false;
            }
            byte[] patched = buildForcedReturn(original, methodName, descriptor, value);
            if (patched == null) {
                return false;
            }
            boolean applied = redefine(target, patched);
            if (applied) {
                JQuickLogger.info("forceReturn applied: " + target.getName() + "#" + methodName
                        + descriptor + " -> " + value);
            }
            return applied;
        } catch (Throwable ex) {
            JQuickLogger.error("forceReturn failed for " + target.getName() + "#" + methodName, ex);
            return false;
        }
    }

    /**
     * Builds replacement bytes whose matched method returns {@code value} immediately.
     *
     * <p>The transformation is the same instruction sequence the mocking capability uses: a constant
     * followed by the return instruction of the declared type, injected at the method head. It is
     * exposed separately so it can be unit tested and inspected without a live JVM.
     *
     * @return replacement bytes, or {@code null} when the method could not be matched
     */
    public static byte[] buildForcedReturn(byte[] originalBytes, final String methodName,
                                           final String descriptor, final Object value) {
        if (originalBytes == null || methodName == null || descriptor == null) {
            return null;
        }
        final String returnType = JQuickDescriptorUtil.returnDescriptor(descriptor);
        byte[] patched = JQuickMethodEnhancer.from(originalBytes)
                .match(methodName, descriptor)
                .advice(JQuickMethodAdvice.builder()
                        .onEnter(new JQuickMethodAdvice.EnterHook() {
                            @Override
                            public void onEnter(JQuickMethodContext context) {
                                emitConstantReturn(context.methodVisitor(), returnType, value);
                            }
                        })
                        .build())
                .apply();
        return patched;
    }

    /**
     * Emits {@code <push constant>; <return>} for the declared return type.
     */
    static void emitConstantReturn(MethodVisitor mv, String returnType, Object value) {
        if (JQuickDescriptorUtil.isVoid(returnType)) {
            mv.visitInsn(Opcodes.RETURN);
            return;
        }
        pushConstant(mv, returnType, value);
        JQuickBytecodeEmitter.emitReturn(mv, returnType);
    }

    private static void pushConstant(MethodVisitor mv, String returnType, Object value) {
        char tag = returnType.charAt(0);
        switch (tag) {
            case 'Z':
                mv.visitInsn(Boolean.TRUE.equals(value) ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
                return;
            case 'B':
            case 'C':
            case 'S':
            case 'I':
                JQuickBytecodeEmitter.pushInt(mv, asInt(value));
                return;
            case 'J':
                long longValue = asLong(value);
                if (longValue == 0L) {
                    mv.visitInsn(Opcodes.LCONST_0);
                } else if (longValue == 1L) {
                    mv.visitInsn(Opcodes.LCONST_1);
                } else {
                    mv.visitLdcInsn(Long.valueOf(longValue));
                }
                return;
            case 'F':
                float floatValue = asFloat(value);
                if (floatValue == 0f) {
                    mv.visitInsn(Opcodes.FCONST_0);
                } else if (floatValue == 1f) {
                    mv.visitInsn(Opcodes.FCONST_1);
                } else if (floatValue == 2f) {
                    mv.visitInsn(Opcodes.FCONST_2);
                } else {
                    mv.visitLdcInsn(Float.valueOf(floatValue));
                }
                return;
            case 'D':
                double doubleValue = asDouble(value);
                if (doubleValue == 0d) {
                    mv.visitInsn(Opcodes.DCONST_0);
                } else if (doubleValue == 1d) {
                    mv.visitInsn(Opcodes.DCONST_1);
                } else {
                    mv.visitLdcInsn(Double.valueOf(doubleValue));
                }
                return;
            default:
                if (value instanceof String || value instanceof Integer || value instanceof Long
                        || value instanceof Float || value instanceof Double
                        || value instanceof Class) {
                    mv.visitLdcInsn(value);
                } else {
                    // A reference type we cannot express as a constant pool entry: return null, which
                    // is the only safe "no value" for a reference return type.
                    mv.visitInsn(Opcodes.ACONST_NULL);
                }
        }
    }

    private static int asInt(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof Boolean) {
            return ((Boolean) value) ? 1 : 0;
        }
        if (value instanceof Character) {
            return ((Character) value).charValue();
        }
        return 0;
    }

    private static long asLong(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private static float asFloat(Object value) {
        return value instanceof Number ? ((Number) value).floatValue() : 0f;
    }

    private static double asDouble(Object value) {
        return value instanceof Number ? ((Number) value).doubleValue() : 0d;
    }

    /**
     * Reads the bytes of a loaded class from its resource, which is how a replacement is built
     * without keeping a copy of every class on the heap.
     */
    public static byte[] readClassBytes(Class<?> target) {
        if (target == null) {
            return null;
        }
        String resource = target.getName().replace('.', '/') + ".class";
        ClassLoader loader = target.getClassLoader();
        java.io.InputStream in = loader == null
                ? ClassLoader.getSystemResourceAsStream(resource)
                : loader.getResourceAsStream(resource);
        if (in == null) {
            return null;
        }
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(4096);
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (Exception ex) {
            JQuickLogger.suppressed("failed to read the bytes of " + target.getName(), ex);
            return null;
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
                // Nothing useful to do.
            }
        }
    }
}
