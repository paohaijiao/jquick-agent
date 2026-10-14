package io.github.paohaijiao.jquick.agent;

import io.github.paohaijiao.jquick.agent.core.JQuickMethodEvent;
import io.github.paohaijiao.jquick.agent.core.JQuickMethodMeta;
import io.github.paohaijiao.jquick.agent.core.JQuickMethodRegistry;
import io.github.paohaijiao.jquick.agent.plugin.component.JQuickMessageHeaders;
import io.github.paohaijiao.jquick.agent.plugin.thread.JQuickThreadPlugin;
import io.github.paohaijiao.jquick.agent.plugin.trace.JQuickTracePlugin;
import io.github.paohaijiao.jquick.agent.runtime.JQuickSink;
import io.github.paohaijiao.jquick.agent.support.JQuickIdGenerator;
import io.github.paohaijiao.jquick.agent.test.JQuickHarness;
import io.github.paohaijiao.jquick.agent.test.JQuickRecordingListener;
import io.github.paohaijiao.jquick.agent.trace.JQuickSpan;
import io.github.paohaijiao.jquick.agent.trace.JQuickSpanType;
import io.github.paohaijiao.jquick.agent.trace.JQuickTraceContext;
import io.github.paohaijiao.jquick.agent.trace.JQuickTraceSampler;
import io.github.paohaijiao.jquick.agent.trace.JQuickTraceSnapshot;
import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the trace runtime: identity generation, scope nesting, sampling, cross-thread
 * propagation and the header used between services.
 *
 * @author Martin
 */
public class TraceRuntimeTest {

    private static final String SERVICE = "example.app.FixtureService";

    private static final String RULES = "includes=example.app.*;quiet=true";

    /* ================================================================== identity */

    /**
     * Ids must be fixed width and hex, because the whole point of a 128-bit trace id is that it can be
     * joined across systems that never agreed on a format.
     */
    @Test
    public void identifiersAreWellFormedAndDistinct() {
        Set<String> traceIds = new HashSet<String>();
        Set<String> spanIds = new HashSet<String>();
        for (int i = 0; i < 200; i++) {
            String traceId = JQuickIdGenerator.newTraceId();
            String spanId = JQuickIdGenerator.newSpanId();
            assertEquals(32, traceId.length());
            assertEquals(16, spanId.length());
            assertTrue("trace id must be hex: " + traceId, traceId.matches("[0-9a-f]{32}"));
            assertTrue("span id must be hex: " + spanId, spanId.matches("[0-9a-f]{16}"));
            traceIds.add(traceId);
            spanIds.add(spanId);
        }
        assertEquals("200 trace ids must all differ", 200, traceIds.size());
        assertEquals("200 span ids must all differ", 200, spanIds.size());
    }

    /* ================================================================== sampling */

    @Test
    public void samplerHonoursTheConfiguredRate() {
        assertTrue(JQuickTraceSampler.ALWAYS.shouldSample());
        assertTrue(JQuickTraceSampler.probabilistic(1d).shouldSample());
        assertTrue("a rate above 1 is clamped to always", JQuickTraceSampler.probabilistic(5d).shouldSample());
        assertFalse(JQuickTraceSampler.probabilistic(0d).shouldSample());
        assertFalse(JQuickTraceSampler.probabilistic(-1d).shouldSample());

        // A mid-range rate is probabilistic by definition; assert only that it is a valid sampler and
        // that it keeps some traffic, never an exact count (which would make the suite flaky).
        JQuickTraceSampler half = JQuickTraceSampler.probabilistic(0.5d);
        assertTrue("the sampler must describe its rate: " + half, half.toString().contains("0.5"));
        int kept = 0;
        for (int i = 0; i < 400; i++) {
            if (half.shouldSample()) {
                kept++;
            }
        }
        assertTrue("a 0.5 sampler must keep some traces", kept > 0);
        assertTrue("a 0.5 sampler must drop some traces", kept < 400);
    }

    /* ================================================================== propagation */

    /**
     * The thread-pool hook, end to end: the task submitted to the pool must observe the trace id of
     * the thread that submitted it.
     *
     * <p>This is the classic failure the hook exists to fix. A pooled thread outlives the trace that
     * created it, so without propagation a task either inherits whatever trace the worker happened to
     * be carrying or starts a brand new one — and an asynchronous branch of a request becomes
     * untraceable.
     */
    @Test
    public void threadPoolSubmissionCarriesTheTraceId() throws Exception {
        JQuickHarness harness = JQuickHarness.ofPlugins(
                RULES + ";thread.enabled=true", new JQuickTracePlugin(), new JQuickThreadPlugin());
        JQuickRecordingListener listener = new JQuickRecordingListener();
        harness.environment().addListener(listener);
        assertTrue("the thread plugin must switch the emitter's pool hook on",
                harness.environment().isThreadPoolWrapping());

        Class<?> service = harness.load(SERVICE);
        Object instance = service.getDeclaredConstructor().newInstance();

        final String[] observed = new String[1];
        Runnable task = new Runnable() {
            @Override
            public void run() {
                observed[0] = JQuickTraceContext.getTraceId();
            }
        };
        call(service, instance, "runTask", new Class<?>[]{Runnable.class}, task);

        JQuickMethodEvent submit = listener.byMethod(JQuickMethodEvent.JQuickPhase.ENTER, "runTask");
        assertNotNull(submit);
        assertNotNull("the task must see a trace id", observed[0]);
        assertEquals("the task must inherit the submitting thread's trace",
                submit.getTraceId(), observed[0]);
    }

    /**
     * A snapshot is what crosses a thread boundary, so restoring it must bring back the whole scope —
     * trace id, current span and sampling decision — not just the id.
     */
    @Test
    public void snapshotRestoresTheWholeScope() throws Exception {
        JQuickSink.reset();
        JQuickTraceContext.setTraceId("0123456789abcdef0123456789abcdef");
        JQuickSink.enter("a#one()");
        final String outerSpan = JQuickTraceContext.getSpanId();
        final JQuickTraceSnapshot snapshot = JQuickTraceContext.capture();

        final Object[] holder = new Object[3];
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                // A fresh thread has nothing to inherit, which is exactly the production situation.
                holder[0] = JQuickTraceContext.getTraceId();
                JQuickTraceContext.restore(snapshot);
                holder[1] = JQuickTraceContext.getTraceId();
                holder[2] = JQuickTraceContext.getSpanId();
            }
        });
        worker.start();
        worker.join(5000L);

        assertNull("a fresh thread starts without a trace", holder[0]);
        assertEquals("0123456789abcdef0123456789abcdef", holder[1]);
        assertEquals(outerSpan, holder[2]);
        JQuickSink.exit("a#one()");
        JQuickSink.reset();
    }

    /* ================================================================== headers */

    /**
     * The propagation header must survive a round trip, because that is the only thing joining two
     * services that share nothing but a socket.
     */
    @Test
    public void messageHeadersRoundTrip() {
        JQuickSink.reset();
        JQuickTraceContext.setTraceId("aaaabbbbccccddddaaaabbbbccccdddd");
        JQuickSink.enter("producer#send()");

        Map<String, String> headers = new HashMap<String, String>();
        assertTrue(JQuickMessageHeaders.applyTo(headers));
        assertEquals(3, headers.size());
        assertNotNull(headers.get(JQuickMessageHeaders.TRACE_ID));
        assertNotNull(headers.get(JQuickMessageHeaders.SPAN_ID));

        String localSpanId = JQuickTraceContext.getSpanId();
        JQuickSink.exit("producer#send()");
        JQuickTraceContext.clear();
        assertFalse(JQuickTraceContext.isActive());

        assertTrue("the consumer must pick the trace up", JQuickMessageHeaders.extract(headers));
        assertEquals("aaaabbbbccccddddaaaabbbbccccdddd", JQuickTraceContext.getTraceId());
        assertEquals("the consumer's first span must be a child of the producer's",
                localSpanId, JQuickTraceContext.getSpanId());
        JQuickTraceContext.clear();
    }

    @Test
    public void messageHeadersAreAbsentWhenNoTraceIsActive() {
        JQuickSink.reset();
        JQuickTraceContext.clear();
        Map<String, String> headers = new HashMap<String, String>();
        assertFalse(JQuickMessageHeaders.applyTo(headers));
        assertTrue(headers.isEmpty());
        assertFalse(JQuickMessageHeaders.extract(headers));
        assertFalse(JQuickMessageHeaders.extract(null));
    }

    /* ================================================================== rendering */

    /**
     * A span must render into something a human can read in a log file, with the nesting visible and
     * the failure called out.
     */
    @Test
    public void spanRendersReadably() {
        JQuickSpan span = JQuickSpan.builder()
                .traceId("t")
                .spanId("s")
                .parentSpanId("p")
                .type(JQuickSpanType.EXIT)
                .component("jdbc")
                .operation("example.app.OrderDao#find(long)")
                .className("example.app.OrderDao")
                .methodName("find")
                .threadName("main")
                .depth(2)
                .startNanos(1_000_000L)
                .durationNanos(5_000_000L)
                .success(false)
                .errorType("java.sql.SQLException")
                .errorMessage("timeout")
                .tag("slow", "true")
                .build();

        String rendered = span.format();
        assertTrue(rendered.contains("find"));
        assertTrue(rendered.contains("jdbc"));
        assertTrue("an outgoing span must be distinguishable", rendered.contains("<<"));
        assertTrue(rendered.contains("ERROR=java.sql.SQLException: timeout"));
        assertTrue("the nesting level must be visible as indentation", rendered.startsWith("    "));
        assertTrue(rendered.contains("[5ms]"));
        assertEquals(5L, span.getDurationMillis());
    }

    /**
     * Span type is a merge, not an overwrite: two plugins may tag the same method, and the most
     * specific claim must win — a class that is an entry point and a method that is merely local
     * produces an entry span.
     */
    @Test
    public void spanTypesMergeByPriority() {
        assertEquals(JQuickSpanType.ENTRY, JQuickSpanType.parse("entry"));
        assertEquals(JQuickSpanType.EXIT, JQuickSpanType.parse("EXIT"));
        assertEquals(JQuickSpanType.LOCAL, JQuickSpanType.parse("nonsense"));
        assertEquals(JQuickSpanType.LOCAL, JQuickSpanType.parse(null));
        assertTrue(JQuickSpanType.ENTRY.priority() > JQuickSpanType.EXIT.priority());
        assertTrue(JQuickSpanType.EXIT.priority() > JQuickSpanType.LOCAL.priority());
    }

    /**
     * Registration merges rather than overwrites, because the tracer and an integration plugin both
     * tag the same method in the real agent.
     */
    @Test
    public void registrationMergesSpanTypeAndComponent() {
        JQuickMethodRegistry registry = new JQuickMethodRegistry();
        registry.register("a#b()", "A", "b", "(V)V", 0, JQuickSpanType.LOCAL, "method");
        registry.register("a#b()", "A", "b", "(V)V", 0, JQuickSpanType.ENTRY, "http");

        JQuickMethodMeta meta = registry.find("a#b()");
        assertNotNull(meta);
        assertEquals(JQuickSpanType.ENTRY, meta.getSpanType());
        assertTrue("components accumulate rather than replace: " + meta.getComponent(),
                meta.getComponent().contains("method") && meta.getComponent().contains("http"));
        assertEquals(1, registry.size());
    }

    /* ================================================================== helpers */

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
