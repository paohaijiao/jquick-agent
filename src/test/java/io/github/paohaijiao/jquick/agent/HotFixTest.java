package io.github.paohaijiao.jquick.agent;

import io.github.paohaijiao.jquick.agent.plugin.hotfix.JQuickHotFixManager;
import io.github.paohaijiao.jquick.agent.support.JQuickDescriptorUtil;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Tests for the hot-fix primitive: building replacement bytes in which one method returns a constant.
 *
 * <p>The live redefinition half of the capability needs a real {@code Instrumentation} and is
 * therefore out of reach of a unit test. The valuable half is testable: {@code buildForcedReturn} is a
 * pure byte-code transformation, and every one of its branches corresponds to a different JVM return
 * type whose constant must be pushed with the right opcode. Getting one of them wrong produces a
 * {@code VerifyError} or a silent zero, so each type is asserted by loading the patched class and
 * calling the method.
 *
 * @author Martin
 */
public class HotFixTest {

    private static final String FIXTURE = "example.app.FixtureService";

    /* ================================================================== primitive return types */

    /**
     * The four value-returning integer-family types share {@code IRETURN} but not the same conversion;
     * a {@code boolean} constant is an iconst, and the JVM will not accept anything else.
     */
    @Test
    public void booleanReturnIsForcedToTheRequestedConstant() throws Exception {
        assertForced("isPositive", "(I)Z", Boolean.TRUE, true, 1);
        assertForced("isPositive", "(I)Z", Boolean.FALSE, false, 1);
        // The argument says "true" but the patch says "true" as well — the argument is deliberately
        // a value the body would have classified the other way, so passing proves the body is gone.
        assertForced("isPositive", "(I)Z", Boolean.TRUE, true, -5);
    }

    @Test
    public void intReturnIsForcedIncludingBeyondTheOneByteRange() throws Exception {
        assertForced("add", "(II)I", Integer.valueOf(0), 0, 1, 2);
        assertForced("add", "(II)I", Integer.valueOf(-1), -1, 1, 2);
        // A value outside the SIPUSH range needs the constant pool, so the wide path is exercised too.
        assertForced("add", "(II)I", Integer.valueOf(100_000), 100_000, 1, 2);
    }

    /**
     * {@code char} is a category-1 integral value but has its own conversion from the boxed
     * {@code Character}, and a PUTFIELD-style mistake here shows up as a wrong character rather than
     * an exception.
     */
    @Test
    public void charReturnIsForcedFromABoxedCharacter() throws Exception {
        assertForced("initial", "(Ljava/lang/String;)C", Character.valueOf('Z'), 'Z', "abc");
    }

    /**
     * A {@code long} is two stack slots wide, so its constants are the {@code LCONST}/{@code LDC}
     * family — a category-2 value in a category-1 emission is the classic verifier failure.
     */
    @Test
    public void longReturnIsForcedIncludingTheCategoryTwoConstants() throws Exception {
        assertForced("widen", "(I)J", Long.valueOf(0L), 0L, 7);
        assertForced("widen", "(I)J", Long.valueOf(1L), 1L, 7);
        assertForced("widen", "(I)J", Long.valueOf(9_999_999_999L), 9_999_999_999L, 7);
    }

    @Test
    public void floatReturnIsForcedIncludingTheShorthandConstants() throws Exception {
        assertForced("ratio", "(II)F", Float.valueOf(0f), 0f, 2, 4);
        assertForced("ratio", "(II)F", Float.valueOf(1f), 1f, 2, 4);
        assertForced("ratio", "(II)F", Float.valueOf(2f), 2f, 2, 4);
        assertForced("ratio", "(II)F", Float.valueOf(2.5f), 2.5f, 2, 4);
    }

    @Test
    public void doubleReturnIsForcedIncludingTheShorthandConstants() throws Exception {
        assertForced("half", "(D)D", Double.valueOf(0d), 0d, 10d);
        assertForced("half", "(D)D", Double.valueOf(1d), 1d, 10d);
        assertForced("half", "(D)D", Double.valueOf(-0.75d), -0.75d, 10d);
    }

    /* ================================================================== reference and void */

    @Test
    public void stringReturnIsForcedToTheConstant() throws Exception {
        assertForced("greet", "(Ljava/lang/String;)Ljava/lang/String;", "patched", "patched", "orig");
    }

    /**
     * A reference constant that cannot be expressed in the constant pool must degrade to {@code null}
     * rather than produce a broken class. {@code null} is the only safe "no value" for a reference
     * return, and a hot fix that refuses to apply is far better than one that corrupts a class.
     */
    @Test
    public void unrepresentableReferenceConstantDegradesToNull() throws Exception {
        assertNull(assertForced("greet", "(Ljava/lang/String;)Ljava/lang/String;",
                new Object(), null, "orig"));
    }

    /**
     * A {@code void} method is patched with a bare {@code RETURN}; the body is skipped entirely, which
     * is observable through the counter the body increments.
     */
    @Test
    public void voidReturnIsForcedToAnImmediateReturn() throws Exception {
        byte[] patched = JQuickHotFixManager.buildForcedReturn(
                read(FIXTURE), "touch", "()V", null);
        assertNotNull("the method must have been matched", patched);

        Class<?> service = loadPatched(patched);
        Object instance = service.getDeclaredConstructor().newInstance();
        service.getDeclaredMethod("touch").invoke(instance);

        assertEquals("the body must have been skipped", 0,
                ((java.util.concurrent.atomic.AtomicInteger) service.getField("CALLS").get(null))
                        .get());
    }

    /* ================================================================== matching rules */

    /**
     * A method that does not exist, or an overload the descriptor does not name, must leave the class
     * untouched. Returning the original bytes instead would make a typo look like a successful patch.
     */
    @Test
    public void unmatchedMethodProducesAnUnchangedClass() throws Exception {
        byte[] original = read(FIXTURE);
        byte[] patched = JQuickHotFixManager.buildForcedReturn(
                original, "noSuchMethod", "(I)I", Integer.valueOf(1));
        assertNotNull("the enhancer still returns valid bytes", patched);

        Class<?> service = loadPatched(patched);
        Object instance = service.getDeclaredConstructor().newInstance();
        assertEquals("an unmatched method must behave exactly as before", 3,
                service.getDeclaredMethod("add", int.class, int.class).invoke(instance, 1, 2));
    }

    /**
     * Only the named overload may be patched. Patching every method of the same name would silently
     * change behaviour the operator never asked about — the difference between a surgical hot fix and
     * an outage.
     */
    @Test
    public void onlyTheNamedOverloadIsPatched() throws Exception {
        byte[] patched = JQuickHotFixManager.buildForcedReturn(
                read(FIXTURE), "tick", "(Ljava/lang/String;JLjava/lang/Object;)Ljava/lang/String;",
                "fixed");
        assertNotNull(patched);

        Class<?> service = loadPatched(patched);
        Object instance = service.getDeclaredConstructor().newInstance();
        assertEquals("the matched overload must be patched", "fixed",
                service.getDeclaredMethod("tick", String.class, long.class, Object.class)
                        .invoke(instance, "a", 1L, "b"));
        assertEquals("an untouched method must keep working", 6000L,
                service.getDeclaredMethod("widen", int.class).invoke(instance, 6));
    }

    /**
     * A defensive-input test: the primitive must reject unusable arguments instead of throwing, so a
     * failed hot fix is a log line rather than a crash inside the diagnostic tool itself.
     */
    @Test
    public void unusableInputIsRejectedWithoutThrowing() {
        assertNull(JQuickHotFixManager.buildForcedReturn(null, "add", "(II)I", Integer.valueOf(1)));
        assertNull(JQuickHotFixManager.buildForcedReturn(read(FIXTURE), null, "(II)I", null));
        assertNull(JQuickHotFixManager.buildForcedReturn(read(FIXTURE), "add", null, null));
        assertFalse("redefinition needs an Instrumentation",
                JQuickHotFixManager.redefine(Object.class, new byte[]{1, 2, 3}));
    }

    /**
     * The forced return must not damage the parts of the class the patch does not touch: the
     * constructor, the other methods and the constant pool all have to survive the round trip.
     */
    @Test
    public void thePatchedClassKeepsEveryOtherMethodIntact() throws Exception {
        byte[] patched = JQuickHotFixManager.buildForcedReturn(
                read(FIXTURE), "add", "(II)I", Integer.valueOf(999));
        Class<?> service = loadPatched(patched);
        Object instance = service.getDeclaredConstructor().newInstance();

        assertEquals(999, service.getDeclaredMethod("add", int.class, int.class)
                .invoke(instance, 1, 2));
        assertEquals("done", service.getDeclaredMethod("selfCall", int.class).invoke(instance, 0));
        assertEquals("caught:negative:-1", service.getDeclaredMethod("guarded", int.class)
                .invoke(instance, -1));
        assertEquals("n=5", service.getDeclaredMethod("staticLabel", int.class).invoke(null, 5));
        assertEquals("the method table must be unchanged — redefinition may not add or remove one",
                methodNames(read(FIXTURE)), methodNames(patched));
    }

    /* ================================================================== helpers */

    /**
     * Patches the method of the fixture, loads the result in a private loader and calls it: the value
     * the caller observes is the assertion. Going through the class loader is what turns "the bytes
     * look right" into "the JVM accepts them".
     */
    private static Object assertForced(String methodName, String descriptor, Object constant,
                                       Object expected, Object... arguments) throws Exception {
        byte[] patched = JQuickHotFixManager.buildForcedReturn(
                read(FIXTURE), methodName, descriptor, constant);
        assertNotNull("no bytes were produced for " + methodName + descriptor, patched);

        Class<?> service = loadPatched(patched);
        Object instance = service.getDeclaredConstructor().newInstance();

        Class<?>[] parameterTypes = parameterTypesOf(descriptor);
        java.lang.reflect.Method method = service.getDeclaredMethod(methodName, parameterTypes);
        Object actual = method.invoke(instance, arguments);
        if (expected instanceof Number && actual instanceof Number) {
            assertEquals("forced return of " + methodName + descriptor,
                    ((Number) expected).doubleValue(), ((Number) actual).doubleValue(), 0.0001d);
        } else {
            assertEquals("forced return of " + methodName + descriptor, expected, actual);
        }
        return actual;
    }

    private static Class<?>[] parameterTypesOf(String descriptor) {
        List<Class<?>> types = new ArrayList<Class<?>>();
        for (String type : JQuickDescriptorUtil.parameterDescriptors(descriptor)) {
            types.add(classOf(type));
        }
        return types.toArray(new Class<?>[0]);
    }

    private static Class<?> classOf(String descriptor) {
        char tag = descriptor.charAt(0);
        switch (tag) {
            case 'Z':
                return boolean.class;
            case 'B':
                return byte.class;
            case 'C':
                return char.class;
            case 'S':
                return short.class;
            case 'I':
                return int.class;
            case 'J':
                return long.class;
            case 'F':
                return float.class;
            case 'D':
                return double.class;
            case 'L':
                String name = descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
                try {
                    return Class.forName(name, false, HotFixTest.class.getClassLoader());
                } catch (ClassNotFoundException ex) {
                    throw new IllegalStateException("unknown type " + descriptor, ex);
                }
            default:
                throw new IllegalStateException("unsupported parameter type " + descriptor);
        }
    }

    private static List<String> methodNames(byte[] classBytes) {
        final List<String> names = new ArrayList<String>();
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                names.add(name + descriptor);
                return null;
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return names;
    }

    private static Class<?> loadPatched(byte[] classBytes) {
        return new PatchedLoader(HotFixTest.class.getClassLoader())
                .define(FIXTURE, classBytes);
    }

    /**
     * Reads the fixture bytes. Failures are unchecked so that the defensive-input test — which asserts
     * the primitive never throws — does not have to declare a checked exception of its own.
     */
    private static byte[] read(String className) {
        String resource = className.replace('.', '/') + ".class";
        InputStream stream = HotFixTest.class.getClassLoader().getResourceAsStream(resource);
        if (stream == null) {
            throw new IllegalStateException("fixture not on the test classpath: " + resource);
        }
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(4096);
            byte[] chunk = new byte[4096];
            int count;
            while ((count = stream.read(chunk)) > 0) {
                buffer.write(chunk, 0, count);
            }
            return buffer.toByteArray();
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("could not read " + resource, ex);
        } finally {
            try {
                stream.close();
            } catch (java.io.IOException ignored) {
                // Reading is one shot; a failed close has no consequence here.
            }
        }
    }

    /**
     * Defines the patched class itself rather than delegating to the parent, so the parent's
     * unpatched copy cannot be picked up by accident.
     */
    private static final class PatchedLoader extends ClassLoader {

        PatchedLoader(ClassLoader parent) {
            super(parent);
        }

        Class<?> define(String className, byte[] bytes) {
            return defineClass(className, bytes, 0, bytes.length);
        }
    }
}
