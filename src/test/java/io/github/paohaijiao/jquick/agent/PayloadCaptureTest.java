package io.github.paohaijiao.jquick.agent;

import io.github.paohaijiao.jquick.agent.plugin.trace.JQuickTracePlugin;
import io.github.paohaijiao.jquick.agent.support.JQuickValueFormatter;
import io.github.paohaijiao.jquick.agent.test.JQuickHarness;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests for the payload half of a trace: arguments, return value and stack trace.
 *
 * <p>These three are the difference between "this call took 12 ms" and "this call took 12 ms because it
 * was asked to pay -1 for alice, and here is where it gave up". They are also the three things a
 * tracing agent is most often accused of <em>claiming</em> to collect without collecting, so each one
 * is asserted against real captured output, and the default configuration is asserted to produce
 * exactly none of it.
 *
 * <p>Capturing {@code System.out} is the only way to observe a trace log. It is worth the awkwardness:
 * asserting on the rendered line is asserting on what an operator would actually see, whereas
 * asserting on a tag map would only prove that a field was populated.
 *
 * @author Martin
 */
public class PayloadCaptureTest {

    private static final String SERVICE = "example.app.FixtureService";

    private static final String LOGGER = "example.app.FixtureLogger";

    private static final String LOGGER_API = "example.app.FixtureLoggerApi";

    private static final String BASE = "includes=example.app.*;quiet=true;trace.enabled=true"
            + ";trace.print=true";

    private PrintStream originalOut;

    private ByteArrayOutputStream captured;

    @Before
    public void captureStdout() throws Exception {
        originalOut = System.out;
        captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, "UTF-8"));
    }

    @After
    public void restoreStdout() {
        System.setOut(originalOut);
    }

    /* ================================================================== arguments */

    @Test
    public void argumentsAreCapturedAndPrintedWhenRequested() throws Exception {
        JQuickHarness harness = JQuickHarness.ofPlugins(BASE + ";trace.args=true",
                new JQuickTracePlugin());
        Class<?> service = harness.load(SERVICE);
        call(service, service.getDeclaredConstructor().newInstance(),
                "greet", new Class<?>[]{String.class}, "world");

        String log = output();
        assertTrue("the arguments must appear on the span line: " + log, log.contains("args=[world]"));
        assertTrue("the signature must still be there", log.contains("#greet(java.lang.String)"));
    }

    /**
     * The default. A tracing agent that captures every argument by default turns a production log into
     * a data-protection incident and pays for the boxing on every call, so the payload is opt-in and
     * the absence is asserted rather than assumed.
     */
    @Test
    public void noPayloadIsPrintedByDefault() throws Exception {
        JQuickHarness harness = JQuickHarness.ofPlugins(BASE, new JQuickTracePlugin());
        Class<?> service = harness.load(SERVICE);
        Object instance = service.getDeclaredConstructor().newInstance();
        call(service, instance, "greet", new Class<?>[]{String.class}, "world");
        call(service, instance, "add", new Class<?>[]{int.class, int.class}, 1, 2);

        String log = output();
        assertTrue("spans must still be printed: " + log, log.contains("#greet(java.lang.String)"));
        assertFalse("no arguments must be rendered: " + log, log.contains("args="));
        assertFalse("no result must be rendered: " + log, log.contains("result="));
    }

    /**
     * The hook plan is what decides this, so asking for the arguments must also change the injected
     * code — otherwise the listener would be handed an invocation that carries nothing.
     */
    @Test
    public void askingForArgumentsChangesTheHookPlan() {
        JQuickHarness plain = JQuickHarness.ofPlugins(BASE, new JQuickTracePlugin());
        assertFalse("no payload means no array is materialised",
                plain.environment().computePlan().isCaptureArguments());
        assertFalse(plain.environment().computePlan().isCaptureResult());

        JQuickHarness full = JQuickHarness.ofPlugins(
                BASE + ";trace.args=true;trace.result=true", new JQuickTracePlugin());
        assertTrue("asking for the arguments must materialise the array",
                full.environment().computePlan().isCaptureArguments());
        assertTrue("asking for the result must box the return value",
                full.environment().computePlan().isCaptureResult());
    }

    /* ================================================================== return value */

    @Test
    public void theReturnValueIsCapturedAndPrintedWhenRequested() throws Exception {
        JQuickHarness harness = JQuickHarness.ofPlugins(BASE + ";trace.args=true;trace.result=true",
                new JQuickTracePlugin());
        Class<?> service = harness.load(SERVICE);
        call(service, service.getDeclaredConstructor().newInstance(),
                "greet", new Class<?>[]{String.class}, "world");

        String log = output();
        assertTrue("the arguments must appear: " + log, log.contains("args=[world]"));
        assertTrue("the return value must appear: " + log, log.contains("result=hello world"));
    }

    /**
     * A {@code void} method has no value to report, and the renderer must not invent one: the trace
     * line says {@code result=null} rather than omitting the field, so a reader can tell "returned
     * nothing" from "the capability is off".
     */
    @Test
    public void aVoidCallReportsAnAbsentResultRatherThanAMissingField() throws Exception {
        JQuickHarness harness = JQuickHarness.ofPlugins(BASE + ";trace.result=true",
                new JQuickTracePlugin());
        Class<?> service = harness.load(SERVICE);
        call(service, service.getDeclaredConstructor().newInstance(), "touch", new Class<?>[]{});

        assertTrue("a void result must be explicit: " + output(), output().contains("result=null"));
    }

    /* ================================================================== stack trace */

    /**
     * The failure path is where a stack trace earns its cost, and it is the one payload the JVM
     * already prepared — so it can be switched on without changing the injected code.
     */
    @Test
    public void theStackIsPrintedForAFailingCallWhenRequested() throws Exception {
        JQuickHarness harness = JQuickHarness.ofPlugins(BASE + ";trace.error.stack=true",
                new JQuickTracePlugin());
        Class<?> service = harness.load(SERVICE);
        try {
            call(service, service.getDeclaredConstructor().newInstance(),
                    "boom", new Class<?>[]{int.class}, 0);
            fail("the fixture should have thrown");
        } catch (IllegalStateException expected) {
            // The point is the log line, not the exception.
        }

        String log = output();
        assertTrue("the exception type must be reported: " + log, log.contains("ERROR="));
        assertTrue("the frames must be reported: " + log, log.contains("at example.app.FixtureService"));
        assertTrue("the top application frame must be present: " + log, log.contains("boom("));
    }

    @Test
    public void noStackIsPrintedByDefault() throws Exception {
        JQuickHarness harness = JQuickHarness.ofPlugins(BASE, new JQuickTracePlugin());
        Class<?> service = harness.load(SERVICE);
        try {
            call(service, service.getDeclaredConstructor().newInstance(),
                    "boom", new Class<?>[]{int.class}, 0);
            fail("the fixture should have thrown");
        } catch (IllegalStateException expected) {
            // Expected.
        }

        String log = output();
        assertTrue("the failure itself must always be reported: " + log, log.contains("ERROR="));
        assertFalse("but not its frames: " + log, log.contains("at example.app.FixtureService"));
    }

    /* ================================================================== formatter */

    /**
     * The rendering rules, asserted directly because they are a safety property rather than a
     * formatting preference: telemetry must not be able to turn a large payload into an incident, and
     * it must not be able to run application code.
     */
    @Test
    public void valuesAreTruncatedAndObjectsAreNeverStringified() {
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            huge.append('x');
        }
        String rendered = JQuickValueFormatter.value(huge.toString(), 64);
        assertTrue("a long value must be cut: " + rendered, rendered.length() < 100);
        assertTrue("and the cut must be visible: " + rendered, rendered.contains("..."));
        assertTrue("stating how much was dropped: " + rendered, rendered.contains("2000 chars"));

        assertEquals("null", JQuickValueFormatter.value(null, 64));
        assertEquals("42", JQuickValueFormatter.value(Integer.valueOf(42), 64));
        assertEquals("true", JQuickValueFormatter.value(Boolean.TRUE, 64));
        assertEquals("[]", JQuickValueFormatter.arguments(null));
        assertEquals("[]", JQuickValueFormatter.arguments(new Object[0]));

        // The important one: rendering a foreign object must not call toString() on it. Doing so would
        // run arbitrary application code, with its side effects, from inside the agent.
        Explosive explosive = new Explosive();
        assertEquals("Explosive", JQuickValueFormatter.value(explosive, 64));
        assertEquals("the agent must never call toString() on an argument",
                0, Explosive.TO_STRING_CALLS.get());
        assertFalse("a name that is merely type-like must not be mistaken for a value",
                JQuickValueFormatter.value(explosive, 64).contains("boom"));
    }

    @Test
    public void theArgumentListIsCappedAndSummarisesTheRemainder() {
        Object[] many = new Object[40];
        Arrays.fill(many, Integer.valueOf(7));
        String rendered = JQuickValueFormatter.arguments(many);

        assertTrue("the list must be capped: " + rendered, rendered.contains("more"));
        assertTrue("the cap must be declared: " + rendered, rendered.contains("24 more"));

        String full = JQuickValueFormatter.arguments(new Object[]{"a", "b"});
        assertEquals("[a, b]", full);
    }

    @Test
    public void theStackRenderingIsCappedAndListsTheFrames() {
        RuntimeException error = new RuntimeException("boom");
        assertTrue("a real throwable has frames", JQuickValueFormatter.stackTrace(error).size() > 1);

        List<String> capped = JQuickValueFormatter.stackTrace(error, 2);
        assertEquals(3, capped.size());
        assertTrue(capped.get(0).startsWith("at "));
        assertTrue("the remainder must be accounted for: " + capped, capped.get(2).contains("more"));

        assertTrue(JQuickValueFormatter.stackTrace(null).isEmpty());
        assertTrue(JQuickValueFormatter.stackTrace(error, 0).isEmpty());

        assertEquals("(no throwable)", JQuickValueFormatter.firstApplicationFrame(null));
        String frame = JQuickValueFormatter.firstApplicationFrame(error);
        assertFalse("the first application frame must not be a JDK frame: " + frame,
                frame.startsWith("java.") || frame.startsWith("sun."));
        assertTrue("and it must be this test: " + frame, frame.contains("PayloadCaptureTest"));
    }

    @Test
    public void aThrowableArgumentIsRenderedByItsMessageNotItsToString() {
        assertEquals("IllegalStateException: boom",
                JQuickValueFormatter.value(new IllegalStateException("boom"), 64));
        assertEquals("IllegalStateException",
                JQuickValueFormatter.value(new IllegalStateException(), 64));
    }

    /* ================================================================== log enhancement */

    /**
     * The log capability rewrites the message argument of the logging call, so the assertion is on what
     * the logger received: the trace id prefix, and — because masking was asked for — no mobile number.
     */
    @Test
    public void theLogMessageCarriesTheTraceIdAndIsMasked() throws Exception {
        JQuickHarness harness = JQuickHarness.ofPlugins(
                BASE + ";log.enabled=true;log.includes=" + LOGGER_API + ";log.mask.enabled=true",
                new io.github.paohaijiao.jquick.agent.plugin.log.JQuickLogPlugin());
        Class<?> logger = harness.load(LOGGER);
        Object instance = logger.getDeclaredConstructor().newInstance();
        List<String> messages = messagesOf(logger);

        call(logger, instance, "info", new Class<?>[]{String.class},
                "user 13800138000 failed to pay");

        assertEquals("exactly one message must have been logged: " + messages, 1, messages.size());
        String message = messages.get(0);
        assertTrue("the trace id must prefix the message: " + message, message.startsWith("["));
        assertTrue("the trace id must be a real id: " + message, message.indexOf("] ") > 1);
        assertTrue("the payload must be preserved: " + message, message.contains("failed to pay"));
        assertFalse("the mobile number must not survive: " + message,
                message.contains("13800138000"));
        assertTrue("in a masked form: " + message, message.contains("138****8000"));
    }

    /**
     * Masking is a separate decision from trace-id injection, and the default must leave the message
     * alone: a matcher that guesses wrong censors production logs, which is worse than a leaked digit.
     */
    @Test
    public void maskingIsOffUnlessRequested() throws Exception {
        JQuickHarness harness = JQuickHarness.ofPlugins(
                BASE + ";log.enabled=true;log.includes=" + LOGGER_API,
                new io.github.paohaijiao.jquick.agent.plugin.log.JQuickLogPlugin());
        Class<?> logger = harness.load(LOGGER);
        Object instance = logger.getDeclaredConstructor().newInstance();
        List<String> messages = messagesOf(logger);

        call(logger, instance, "info", new Class<?>[]{String.class}, "user 13800138000 failed");

        String message = messages.get(0);
        assertTrue("the trace id is still injected: " + message, message.startsWith("["));
        assertTrue("but the message must be untouched: " + message,
                message.contains("13800138000"));
    }

    /**
     * A parameterised logging call keeps working: the template is rewritten, the parameters are not,
     * and the mask still sees the whole message because it runs on the message the logger receives.
     */
    @Test
    public void onlyTheMessageArgumentIsRewritten() throws Exception {
        JQuickHarness harness = JQuickHarness.ofPlugins(
                BASE + ";log.enabled=true;log.includes=" + LOGGER_API + ";log.mask.enabled=true",
                new io.github.paohaijiao.jquick.agent.plugin.log.JQuickLogPlugin());
        Class<?> logger = harness.load(LOGGER);
        Object instance = logger.getDeclaredConstructor().newInstance();
        List<String> messages = messagesOf(logger);

        call(logger, instance, "error", new Class<?>[]{String.class, Throwable.class},
                "13800138000@example.com rejected", new IllegalArgumentException("bad"));

        String message = messages.get(0);
        assertTrue("the trace id must be injected: " + message, message.startsWith("["));
        assertFalse("the identity must be masked: " + message, message.contains("13800138000"));
        assertFalse("including the e-mail: " + message, message.contains("13800138000@example.com"));
        assertTrue("the throwable argument must be passed through untouched: " + message,
                message.endsWith("| IllegalArgumentException"));
    }

    /* ================================================================== helpers */

    private static final class Explosive {

        static final AtomicInteger TO_STRING_CALLS = new AtomicInteger();

        @Override
        public String toString() {
            TO_STRING_CALLS.incrementAndGet();
            return "boom";
        }
    }

    private String output() {
        return captured.toString();
    }

    /**
     * Reads the record list through the loaded class.
     *
     * <p>The harness defines the fixture in a private class loader, so the {@code MESSAGES} field on
     * the enhanced class is a <em>different</em> static from the one reachable through
     * {@code example.app.FixtureLogger} in this class loader. Reading the parent's copy would assert
     * against an empty list forever — the classic way for a test like this to pass while proving nothing.
     */
    @SuppressWarnings("unchecked")
    private static List<String> messagesOf(Class<?> logger) throws Exception {
        List<String> messages = (List<String>) logger.getField("MESSAGES").get(null);
        messages.clear();
        return messages;
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
