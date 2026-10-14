package io.github.paohaijiao.jquick.agent.support;

import java.util.ArrayList;
import java.util.List;

/**
 * JVM type-descriptor helpers.
 *
 * <p>Every event produced by the agent carries a <em>pre-computed</em> readable signature such as
 * {@code com.demo.OrderService#pay(java.lang.String, long)}. Computing it at instrumentation time
 * instead of runtime keeps the injected byte-code down to a single {@code LDC} plus an
 * {@code INVOKESTATIC}, which is the difference between a usable and an unusable tracing agent.
 *
 * <p>The same parser also drives argument capture: the enhancer walks the descriptor to know which
 * local slot holds which argument and which JVM type it has.
 *
 * @author Martin
 */
public final class JQuickDescriptorUtil {

    /**
     * Descriptor of {@code void}.
     */
    public static final String VOID = "V";

    private JQuickDescriptorUtil() {
    }

    /**
     * Builds a readable method signature.
     *
     * @param ownerInternalName internal class name, e.g. {@code "com/demo/OrderService"}
     * @param methodName        method name
     * @param descriptor        JVM method descriptor, e.g. {@code "(Ljava/lang/String;J)V"}
     * @return readable signature, e.g. {@code "com.demo.OrderService#pay(java.lang.String, long)"}
     */
    public static String toSignature(String ownerInternalName, String methodName, String descriptor) {
        String owner = ownerInternalName == null
                ? "<unknown>" : ownerInternalName.replace('/', '.');
        return owner + "#" + methodName + toReadableParameters(descriptor);
    }

    /**
     * Converts the parameter part of a descriptor into a readable list.
     */
    public static String toReadableParameters(String descriptor) {
        StringBuilder sb = new StringBuilder("(");
        List<String> parameters = parameterDescriptors(descriptor);
        for (int i = 0; i < parameters.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(toReadableType(parameters.get(i)));
        }
        return sb.append(')').toString();
    }

    /**
     * Converts a single descriptor fragment into a readable type name.
     */
    public static String toReadableType(String descriptor) {
        if (JQuickStringUtil.isBlank(descriptor)) {
            return "?";
        }
        int arrayDepth = 0;
        int index = 0;
        while (index < descriptor.length() && descriptor.charAt(index) == '[') {
            arrayDepth++;
            index++;
        }
        if (index >= descriptor.length()) {
            return descriptor;
        }
        char tag = descriptor.charAt(index);
        String name;
        switch (tag) {
            case 'V':
                name = "void";
                break;
            case 'Z':
                name = "boolean";
                break;
            case 'B':
                name = "byte";
                break;
            case 'C':
                name = "char";
                break;
            case 'S':
                name = "short";
                break;
            case 'I':
                name = "int";
                break;
            case 'J':
                name = "long";
                break;
            case 'F':
                name = "float";
                break;
            case 'D':
                name = "double";
                break;
            case 'L':
                name = descriptor.substring(index + 1, descriptor.length() - 1).replace('/', '.');
                break;
            default:
                name = descriptor;
                break;
        }
        StringBuilder sb = new StringBuilder(name);
        for (int i = 0; i < arrayDepth; i++) {
            sb.append("[]");
        }
        return sb.toString();
    }

    /**
     * Splits the parameter part of a method descriptor into individual type descriptors.
     */
    public static List<String> parameterDescriptors(String methodDescriptor) {
        List<String> types = new ArrayList<String>();
        if (methodDescriptor == null) {
            return types;
        }
        int start = methodDescriptor.indexOf('(');
        int end = methodDescriptor.indexOf(')');
        if (start < 0 || end <= start) {
            return types;
        }
        String args = methodDescriptor.substring(start + 1, end);
        int index = 0;
        while (index < args.length()) {
            int next = skipType(args, index);
            types.add(args.substring(index, next));
            index = next;
        }
        return types;
    }

    /**
     * Returns the return type descriptor, e.g. {@code "V"} or {@code "Ljava/lang/String;"}.
     */
    public static String returnDescriptor(String methodDescriptor) {
        if (methodDescriptor == null) {
            return VOID;
        }
        int end = methodDescriptor.indexOf(')');
        if (end < 0 || end + 1 >= methodDescriptor.length()) {
            return VOID;
        }
        return methodDescriptor.substring(end + 1);
    }

    /**
     * Whether the descriptor denotes {@code void}.
     */
    public static boolean isVoid(String typeDescriptor) {
        return VOID.equals(typeDescriptor);
    }

    /**
     * Whether the descriptor denotes a primitive (including {@code void}).
     */
    public static boolean isPrimitive(String typeDescriptor) {
        return typeDescriptor != null
                && typeDescriptor.length() == 1
                && typeDescriptor.charAt(0) != 'L'
                && typeDescriptor.charAt(0) != '[';
    }

    /**
     * Number of local-variable slots a single value of this type occupies (2 for {@code long} and
     * {@code double}, 1 otherwise).
     */
    public static int slotSize(String typeDescriptor) {
        if (typeDescriptor == null || typeDescriptor.isEmpty()) {
            return 1;
        }
        char tag = typeDescriptor.charAt(0);
        return (tag == 'J' || tag == 'D') ? 2 : 1;
    }

    /**
     * Total number of local-variable slots occupied by the parameters of a method.
     *
     * <p>For a non-static method the receiver occupies slot 0, so the first argument starts at
     * index 1; the enhancer uses this value both to locate arguments and to reserve a scratch slot
     * for re-writing transformed arguments.
     */
    public static int parameterSlotCount(String methodDescriptor) {
        int slots = 0;
        for (String type : parameterDescriptors(methodDescriptor)) {
            slots += slotSize(type);
        }
        return slots;
    }

    /**
     * Load opcode for a value of the given type ({@code ILOAD}, {@code LLOAD}, ... {@code ALOAD}).
     */
    public static int loadOpcode(String typeDescriptor) {
        if (typeDescriptor == null || typeDescriptor.isEmpty()) {
            return org.objectweb.asm.Opcodes.ALOAD;
        }
        char tag = typeDescriptor.charAt(0);
        switch (tag) {
            case 'Z':
            case 'B':
            case 'C':
            case 'S':
            case 'I':
                return org.objectweb.asm.Opcodes.ILOAD;
            case 'J':
                return org.objectweb.asm.Opcodes.LLOAD;
            case 'F':
                return org.objectweb.asm.Opcodes.FLOAD;
            case 'D':
                return org.objectweb.asm.Opcodes.DLOAD;
            default:
                return org.objectweb.asm.Opcodes.ALOAD;
        }
    }

    /**
     * Store opcode for a value of the given type ({@code ISTORE}, {@code LSTORE}, ... {@code ASTORE}).
     */
    public static int storeOpcode(String typeDescriptor) {
        if (typeDescriptor == null || typeDescriptor.isEmpty()) {
            return org.objectweb.asm.Opcodes.ASTORE;
        }
        char tag = typeDescriptor.charAt(0);
        switch (tag) {
            case 'Z':
            case 'B':
            case 'C':
            case 'S':
            case 'I':
                return org.objectweb.asm.Opcodes.ISTORE;
            case 'J':
                return org.objectweb.asm.Opcodes.LSTORE;
            case 'F':
                return org.objectweb.asm.Opcodes.FSTORE;
            case 'D':
                return org.objectweb.asm.Opcodes.DSTORE;
            default:
                return org.objectweb.asm.Opcodes.ASTORE;
        }
    }

    /**
     * Returns the index right after the type starting at {@code from}.
     */
    private static int skipType(String descriptor, int from) {
        int index = from;
        while (index < descriptor.length() && descriptor.charAt(index) == '[') {
            index++;
        }
        if (index >= descriptor.length()) {
            return descriptor.length();
        }
        if (descriptor.charAt(index) == 'L') {
            int end = descriptor.indexOf(';', index);
            return end < 0 ? descriptor.length() : end + 1;
        }
        return index + 1;
    }
}
