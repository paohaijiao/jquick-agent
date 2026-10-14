package io.github.paohaijiao.jquick.agent.support;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Low-level instruction emitter used by the enhancers.
 *
 * <p>jquick-asm owns the <em>visitor plumbing</em> (matching, guard rails, frame computation, class
 * writing); this class owns the few instruction sequences the agent needs to emit at the advice
 * hook points. Keeping them here means every enhancer produces byte-code that is structurally
 * identical, which makes the injected code easy to audit.
 *
 * <p>The emitted sequences are deliberately straight-line — no branches, no new exception handlers.
 * That is what allows the agent to preserve the application's own {@code try/catch} semantics
 * byte-for-byte.
 *
 * @author Martin
 */
public final class JQuickBytecodeEmitter {

    /**
     * Internal name of the array type used to carry captured arguments.
     */
    public static final String OBJECT_ARRAY_DESCRIPTOR = "[Ljava/lang/Object;";

    /**
     * Internal name of {@code java.lang.Object}.
     */
    public static final String OBJECT_INTERNAL_NAME = "java/lang/Object";

    private static final String[] WRAPPERS = new String[]{
            "java/lang/Boolean", "java/lang/Byte", "java/lang/Character", "java/lang/Short",
            "java/lang/Integer", "java/lang/Long", "java/lang/Float", "java/lang/Double"
    };

    private static final char[] PRIMITIVE_TAGS = new char[]{'Z', 'B', 'C', 'S', 'I', 'J', 'F', 'D'};

    private JQuickBytecodeEmitter() {
    }

    /**
     * Pushes an {@code int} constant using the shortest instruction form.
     */
    public static void pushInt(MethodVisitor mv, int value) {
        if (value >= -1 && value <= 5) {
            mv.visitInsn(Opcodes.ICONST_0 + value);
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            mv.visitIntInsn(Opcodes.BIPUSH, value);
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            mv.visitIntInsn(Opcodes.SIPUSH, value);
        } else {
            mv.visitLdcInsn(Integer.valueOf(value));
        }
    }

    /**
     * Loads a local variable onto the operand stack according to its declared type.
     */
    public static void load(MethodVisitor mv, String typeDescriptor, int slot) {
        mv.visitVarInsn(JQuickDescriptorUtil.loadOpcode(typeDescriptor), slot);
    }

    /**
     * Stores the operand stack top into a local variable according to its declared type.
     */
    public static void store(MethodVisitor mv, String typeDescriptor, int slot) {
        mv.visitVarInsn(JQuickDescriptorUtil.storeOpcode(typeDescriptor), slot);
    }

    /**
     * Boxes a primitive value sitting on the stack top into its wrapper type.
     *
     * <p>Reference types (including arrays) are already objects for the verifier and are left
     * untouched.
     */
    public static void box(MethodVisitor mv, String typeDescriptor) {
        int index = primitiveIndex(typeDescriptor);
        if (index < 0) {
            return;
        }
        String wrapper = WRAPPERS[index];
        char tag = PRIMITIVE_TAGS[index];
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, wrapper, "valueOf",
                "(" + tag + ")L" + wrapper + ";", false);
    }

    /**
     * Unboxes an {@code Object} sitting on the stack top, leaving a value of the declared type.
     *
     * <p>Reference types need a {@code CHECKCAST} because the verifier does not know what
     * {@code Object} the runtime actually produced. The name used for that cast is the subtle part:
     * an <b>array</b> is named by its descriptor ({@code [I}, {@code [[Ljava/lang/String;}) and an
     * object type by its internal name ({@code java/util/List}). Stripping the leading {@code L} and
     * the trailing {@code ;} off an array descriptor yields an empty string, which ASM rejects deep
     * inside frame computation with a {@code StringIndexOutOfBoundsException} — a misleading failure
     * for a one-character mistake, and the reason this is spelled out in a helper.
     */
    public static void unbox(MethodVisitor mv, String typeDescriptor) {
        if (typeDescriptor == null || JQuickDescriptorUtil.isVoid(typeDescriptor)) {
            mv.visitInsn(Opcodes.POP);
            return;
        }
        int index = primitiveIndex(typeDescriptor);
        if (index < 0) {
            mv.visitTypeInsn(Opcodes.CHECKCAST, castTarget(typeDescriptor));
            return;
        }
        String wrapper = WRAPPERS[index];
        char tag = PRIMITIVE_TAGS[index];
        mv.visitTypeInsn(Opcodes.CHECKCAST, wrapper);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, wrapper, primitiveAccessor(tag),
                "()" + tag, false);
    }

    /**
     * The constant a {@code CHECKCAST} needs for a non-primitive descriptor.
     */
    static String castTarget(String typeDescriptor) {
        if (typeDescriptor == null || typeDescriptor.isEmpty()) {
            return OBJECT_INTERNAL_NAME;
        }
        if (typeDescriptor.charAt(0) == '[') {
            return typeDescriptor;
        }
        if (typeDescriptor.length() >= 2
                && typeDescriptor.charAt(0) == 'L'
                && typeDescriptor.charAt(typeDescriptor.length() - 1) == ';') {
            return typeDescriptor.substring(1, typeDescriptor.length() - 1);
        }
        return typeDescriptor;
    }

    /**
     * Emits the return instruction matching the given type.
     */
    public static void emitReturn(MethodVisitor mv, String typeDescriptor) {
        if (typeDescriptor == null || JQuickDescriptorUtil.isVoid(typeDescriptor)) {
            mv.visitInsn(Opcodes.RETURN);
            return;
        }
        switch (typeDescriptor.charAt(0)) {
            case 'Z':
            case 'B':
            case 'C':
            case 'S':
            case 'I':
                mv.visitInsn(Opcodes.IRETURN);
                break;
            case 'J':
                mv.visitInsn(Opcodes.LRETURN);
                break;
            case 'F':
                mv.visitInsn(Opcodes.FRETURN);
                break;
            case 'D':
                mv.visitInsn(Opcodes.DRETURN);
                break;
            default:
                mv.visitInsn(Opcodes.ARETURN);
                break;
        }
    }

    /**
     * Whether a value of this type can be carried through an {@code Object[]} without losing
     * information. Every JVM type can, so this always returns {@code true}; it exists as a named
     * concept for readability at the call sites.
     */
    public static boolean isBoxable(String typeDescriptor) {
        return !JQuickDescriptorUtil.isVoid(typeDescriptor);
    }

    private static int primitiveIndex(String typeDescriptor) {
        if (!JQuickDescriptorUtil.isPrimitive(typeDescriptor)) {
            return -1;
        }
        char tag = typeDescriptor.charAt(0);
        for (int i = 0; i < PRIMITIVE_TAGS.length; i++) {
            if (PRIMITIVE_TAGS[i] == tag) {
                return i;
            }
        }
        return -1;
    }

    private static String primitiveAccessor(char tag) {
        switch (tag) {
            case 'Z':
                return "booleanValue";
            case 'B':
                return "byteValue";
            case 'C':
                return "charValue";
            case 'S':
                return "shortValue";
            case 'I':
                return "intValue";
            case 'J':
                return "longValue";
            case 'F':
                return "floatValue";
            case 'D':
                return "doubleValue";
            default:
                throw new IllegalArgumentException("Not a primitive tag: " + tag);
        }
    }
}
