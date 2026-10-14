package io.github.paohaijiao.jquick.agent;

import io.github.paohaijiao.jquick.agent.core.JQuickMethodEvent;
import io.github.paohaijiao.jquick.agent.plugin.mock.JQuickMockPlugin;
import io.github.paohaijiao.jquick.agent.plugin.trace.JQuickTracePlugin;
import io.github.paohaijiao.jquick.agent.test.JQuickHarness;
import io.github.paohaijiao.jquick.agent.test.JQuickRecordingListener;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests for the byte-code level stubbing capability, exercised through the emitter rather than
 * through the registry.
 *
 * <p>The registry is trivial; the interesting claim is that a stub declared here actually prevents the
 * <em>real</em> body from running. That claim can only be tested by instrumenting the class, loading
 * it, calling it, and observing the side effect the body would have produced. Every test below
 * therefore checks {@code FixtureService.CALLS} — the counter the body increments — and treats a
 * non-zero value as "the stub was not honoured".
 *
 * @author Martin
 */
public class StubbingTest {

    private static final String SERVICE = "example.app.FixtureService";

    private static final String RULES = "includes=example.app.*;quiet=true;mock.enabled=true";

    /* ================================================================== value stubs */

    /**
     * The whole point of the capability: the body is skipped, not merely overridden. A proxy-based
     * mock would still have entered the method, so the side effect would be observable.
     */
    @Test
    public void stubbedMethodReturnsTheStubAndSkipsTheBody() throws Exception {
        JQuickHarness harness = mockHarness();
        JQuickMockPlugin mock = mockPlugin(harness);
        mock.getRegistry().stubReturn(SERVICE + "#add(int, int)", Integer.valueOf(42));

        Class<?> service = harness.load(SERVICE);
        Object instance = service.getDeclaredConstructor().newInstance();

        assertEquals("the stub value must be returned", 42,
                call(service, instance, "add", new Class<?>[]{int.class, int.class}, 1, 2));
        assertEquals("the body must not have run", 0, callsOf(service));
    }

    /**
     * A stub is per signature, never per class: stubbing one method must not disturb its neighbours.
     * That is the property that lets a stub be introduced into an existing suite without rewriting it.
     */
    @Test
    public void stubbingOneMethodLeavesTheOthersAlone() throws Exception {
        JQuickHarness harness = mockHarness();
        mockPlugin(harness).getRegistry()
                .stubReturn(SERVICE + "#add(int, int)", Integer.valueOf(42));

        Class<?> service = harness.load(SERVICE);
        Object instance = service.getDeclaredConstructor().newInstance();

        assertEquals(42, call(service, instance, "add", new Class<?>[]{int.class, int.class}, 1, 2));
        // touch() increments the counter; add() would have too, had its body run.
        call(service, instance, "touch", new Class<?>[]{});
        assertEquals("only the unstubbed call reached its body", 1, callsOf(service));
        assertEquals("a value-returning method not stubbed still computes", 7000L,
                call(service, instance, "widen", new Class<?>[]{int.class}, 7));
    }

    /**
     * A reference return type goes through the same {@code DUP}/{@code IFNULL} branch, and the stub
     * value crosses the instrumentation boundary as an {@code Object} that must be cast back. A
     * {@code String} stub is therefore the test for the object cast path.
     */
    @Test
    public void referenceReturnTypeIsCastBackFromTheStub() throws Exception {
        JQuickHarness harness = mockHarness();
        mockPlugin(harness).getRegistry()
                .stubReturn(SERVICE + "#greet(java.lang.String)", "stubbed greeting");

        Class<?> service = harness.load(SERVICE);
        Object instance = service.getDeclaredConstructor().newInstance();

        assertEquals("stubbed greeting",
                call(service, instance, "greet", new Class<?>[]{String.class}, "world"));
    }

    /**
     * A {@code void} method is stubbed with the {@link io.github.paohaijiao.jquick.agent.core
     * .JQuickStubResolver#VOID} sentinel, and the emitter turns it into a bare {@code RETURN}.
     */
    @Test
    public void voidMethodIsStubbedWithTheVoidSentinel() throws Exception {
        JQuickHarness harness = mockHarness();
        mockPlugin(harness).getRegistry().stubVoid(SERVICE + "#touch()");

        Class<?> service = harness.load(SERVICE);
        Object instance = service.getDeclaredConstructor().newInstance();

        call(service, instance, "touch", new Class<?>[]{});
        assertEquals("a void stub must skip the body", 0, callsOf(service));
    }

    /**
     * The most useful stub when a fallback path or a circuit breaker is under test. The throwable has
     * to be raised from the method head, which means the body never runs and the caller sees the
     * exception as if the method had thrown it itself.
     */
    @Test
    public void stubCanThrowToExerciseAFallbackPath() throws Exception {
        JQuickHarness harness = mockHarness();
        mockPlugin(harness).getRegistry().stubThrow(SERVICE + "#boom(int)",
                new IllegalStateException("dependency is down"));

        Class<?> service = harness.load(SERVICE);
        Object instance = service.getDeclaredConstructor().newInstance();

        try {
            call(service, instance, "boom", new Class<?>[]{int.class}, 7);
            fail("the stub was registered to throw");
        } catch (IllegalStateException expected) {
            assertEquals("dependency is down", expected.getMessage());
        }
    }

    /* ================================================================== stub lifecycle */

    /**
     * Stubs are dynamically scoped by declaration: removing one restores the real implementation
     * without reloading anything, which is what makes a stub usable inside a single test method.
     */
    @Test
    public void unstubbingRestoresTheRealBody() throws Exception {
        JQuickHarness harness = mockHarness();
        JQuickMockPlugin mock = mockPlugin(harness);
        mock.getRegistry().stubReturn(SERVICE + "#add(int, int)", Integer.valueOf(42));

        Class<?> service = harness.load(SERVICE);
        Object instance = service.getDeclaredConstructor().newInstance();

        assertEquals(42, call(service, instance, "add", new Class<?>[]{int.class, int.class}, 1, 2));
        assertTrue(mock.getRegistry().isStubbed(SERVICE + "#add(int, int)"));

        mock.getRegistry().unstub(SERVICE + "#add(int, int)");
        assertFalse(mock.getRegistry().isStubbed(SERVICE + "#add(int, int)"));
        assertEquals("the real body must be back", 3,
                call(service, instance, "add", new Class<?>[]{int.class, int.class}, 1, 2));
    }

    /**
     * A void stub registered against a value-returning method cannot be honoured — the method must
     * put something on the stack for its {@code IRETURN}. The sink refuses it rather than returning a
     * {@code null} that would fail with an obscure {@code ClassCastException} inside the application.
     */
    @Test
    public void voidStubOnANonVoidMethodIsIgnored() throws Exception {
        JQuickHarness harness = mockHarness();
        mockPlugin(harness).getRegistry().stubVoid(SERVICE + "#add(int, int)");

        Class<?> service = harness.load(SERVICE);
        Object instance = service.getDeclaredConstructor().newInstance();

        assertEquals("the real body must run when the stub cannot be honoured", 3,
                call(service, instance, "add", new Class<?>[]{int.class, int.class}, 1, 2));
    }

    /* ================================================================== observability */

    /**
     * A stubbed call must still be visible. Without a span, a stubbed integration test would report
     * green while quietly exercising nothing — the failure mode that makes stubbing dangerous, and
     * the reason the stub path publishes its own event with a distinct component label.
     */
    @Test
    public void aStubIsVisibleInTheSpanStream() throws Exception {
        JQuickHarness harness = mockHarness();
        JQuickRecordingListener listener = new JQuickRecordingListener();
        harness.environment().addListener(listener);
        mockPlugin(harness).getRegistry()
                .stubReturn(SERVICE + "#greet(java.lang.String)", "stubbed");

        Class<?> service = harness.load(SERVICE);
        Object instance = service.getDeclaredConstructor().newInstance();
        assertEquals("stubbed", call(service, instance, "greet", new Class<?>[]{String.class}, "x"));

        JQuickMethodEvent event = listener.byMethod(JQuickMethodEvent.JQuickPhase.EXIT, "greet");
        assertNotNull("a stub must publish a span: " + listener.events(), event);
        assertEquals("mock", event.getSpan().getComponent());
        assertEquals("true", event.getSpan().getTags().get("mock"));
        assertTrue("the span must say which method was stubbed: "
                + event.getSpan().getOperation(), event.getSpan().getOperation().contains("#greet"));
    }

    /**
     * The stub check is a branch injected at the method head, so a class instrumented while mocking
     * was off simply has no such branch. Asserting the negative is what documents the honest
     * boundary: enabling the plugin later cannot retro-fit an already-loaded class.
     */
    @Test
    public void stubsAreNotHonouredWhenMockingWasOffAtInstrumentationTime() throws Exception {
        JQuickHarness harness = JQuickHarness.ofPlugins("includes=example.app.*;quiet=true",
                new JQuickTracePlugin(), new JQuickMockPlugin());
        // A disabled capability is filtered out before initialisation, exactly as in the bootstrap,
        // so no stub resolver exists and the plan carries no stub check at all.
        assertNull("a disabled plugin must not reach the environment", mockRegistryOf(harness));
        assertFalse("without a stub resolver the plan must not request a stub check",
                harness.environment().computePlan().isMockCheck());

        Class<?> service = harness.load(SERVICE);
        Object instance = service.getDeclaredConstructor().newInstance();

        assertEquals("with no stub branch the body always runs", 3,
                call(service, instance, "add", new Class<?>[]{int.class, int.class}, 1, 2));
        assertEquals(1, callsOf(service));
    }

    /* ================================================================== helpers */

    private static JQuickHarness mockHarness() {
        // The mock plugin contributes no enhancer of its own: the stub check is one flag of the hook
        // plan, so it is emitted by whichever capability instruments the class. Pairing it with the
        // tracer is therefore the realistic configuration, not a test shortcut.
        return JQuickHarness.ofPlugins(RULES, new JQuickTracePlugin(), new JQuickMockPlugin());
    }

    /**
     * Retrieves the enabled mock plugin from the environment, so a test can declare stubs against the
     * registry the injected byte-code will actually consult.
     */
    private static JQuickMockPlugin mockPlugin(JQuickHarness harness) {
        JQuickMockPlugin plugin = mockRegistryOf(harness);
        assertNotNull("the mock plugin must be installed", plugin);
        return plugin;
    }

    private static JQuickMockPlugin mockRegistryOf(JQuickHarness harness) {
        for (io.github.paohaijiao.jquick.agent.core.JQuickPlugin plugin
                : harness.environment().getPlugins()) {
            if (plugin instanceof JQuickMockPlugin) {
                return (JQuickMockPlugin) plugin;
            }
        }
        return null;
    }

    private static int callsOf(Class<?> service) throws Exception {
        Field field = service.getField("CALLS");
        return ((AtomicInteger) field.get(null)).get();
    }

    private static Object call(Class<?> type, Object instance, String name,
                               Class<?>[] parameterTypes, Object... arguments) throws Exception {
        Method method = type.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        try {
            return method.invoke(instance, arguments);
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof Exception) {
                throw (Exception) ex.getCause();
            }
            throw ex;
        }
    }
}
