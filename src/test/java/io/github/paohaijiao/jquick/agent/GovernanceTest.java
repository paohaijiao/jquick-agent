package io.github.paohaijiao.jquick.agent;

import io.github.paohaijiao.jquick.agent.core.JQuickRejectedException;
import io.github.paohaijiao.jquick.agent.plugin.governance.JQuickCircuitBreaker;
import io.github.paohaijiao.jquick.agent.plugin.governance.JQuickGovernancePlugin;
import io.github.paohaijiao.jquick.agent.plugin.governance.JQuickRateLimiter;
import io.github.paohaijiao.jquick.agent.plugin.trace.JQuickTracePlugin;
import io.github.paohaijiao.jquick.agent.test.JQuickHarness;
import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests for rate limiting, circuit breaking and the gate that turns them into enforcement.
 *
 * @author Martin
 */
public class GovernanceTest {

    private static final String SERVICE = "example.app.FixtureService";

    private static final String RULES = "includes=example.app.*;quiet=true";

    /* ================================================================== rate limiting */

    /**
     * A token bucket allows a burst, then refuses until it refills. The burst is the point: a limit of
     * one request per second that also refuses the second request of a page load is unusable.
     */
    @Test
    public void rateLimiterAllowsTheBurstThenRefuses() {
        JQuickRateLimiter limiter = new JQuickRateLimiter(0.0001d, 2);
        assertTrue(limiter.tryAcquire("op"));
        assertTrue(limiter.tryAcquire("op"));
        assertFalse("the burst is exhausted", limiter.tryAcquire("op"));
        assertEquals(1, limiter.trackedKeys());

        // Keys are independent: one exhausted key must not throttle another operation.
        assertTrue(limiter.tryAcquire("other"));
        limiter.reset();
    }

    /* ================================================================== circuit breaking */

    /**
     * The breaker must close, open, and then admit a probe — the half-open state is what makes it a
     * recovery mechanism rather than a permanent outage.
     *
     * <p>The open window is measured in nanoseconds, so the test waits it out rather than configuring
     * a zero window: zero would mean "never stay open", which the implementation deliberately maps to
     * the default to keep a misconfiguration from disabling the breaker entirely.
     */
    @Test
    public void circuitBreakerOpensThenAllowsAProbe() throws Exception {
        JQuickCircuitBreaker breaker = new JQuickCircuitBreaker(0.5d, 4, 2, 1L);
        assertEquals(JQuickCircuitBreaker.State.CLOSED, breaker.stateOf("op"));
        assertTrue(breaker.allowRequest("op"));

        breaker.recordFailure("op");
        breaker.recordFailure("op");
        assertEquals("two failures out of two is 100%", 1.0d, breaker.failureRateOf("op"), 0.0001d);
        assertEquals("a 50% threshold must have tripped", JQuickCircuitBreaker.State.OPEN,
                breaker.stateOf("op"));

        Thread.sleep(5L);
        assertTrue("an open circuit must eventually admit a probe", breaker.allowRequest("op"));
        assertEquals(JQuickCircuitBreaker.State.HALF_OPEN, breaker.stateOf("op"));

        breaker.recordSuccess("op");
        assertEquals("a successful probe closes the circuit", JQuickCircuitBreaker.State.CLOSED,
                breaker.stateOf("op"));
        assertEquals("a closed circuit has an empty failure rate", 0.0d,
                breaker.failureRateOf("op"), 0.0001d);
        breaker.reset();
    }

    /**
     * The breaker must not trip on a single failure: a threshold that ignores the sample size turns
     * one transient error at startup into an outage.
     */
    @Test
    public void circuitBreakerIgnoresTooSmallASample() {
        JQuickCircuitBreaker breaker = new JQuickCircuitBreaker(0.1d, 10, 5, 1000L);
        breaker.recordFailure("op");
        assertEquals(JQuickCircuitBreaker.State.CLOSED, breaker.stateOf("op"));
        assertTrue(breaker.allowRequest("op"));
        breaker.reset();
    }

    /* ================================================================== enforcement */

    /**
     * The gate runs in the entry hook, before the method body, so a rejected call never executes. That
     * ordering is the whole security property: a limiter that rejects a call *after* it has done the
     * work protects nothing.
     */
    @Test
    public void rateLimitedCallIsRejectedBeforeTheBodyRuns() throws Exception {
        JQuickHarness harness = JQuickHarness.ofPlugins(
                RULES + ";governance.enabled=true;rate.limit.qps=0.0001;rate.limit.burst=1",
                new JQuickTracePlugin(), new JQuickGovernancePlugin());
        Class<?> service = harness.load(SERVICE);
        Object instance = service.getDeclaredConstructor().newInstance();

        assertEquals(3, call(service, instance, "add", new Class<?>[]{int.class, int.class}, 1, 2));
        try {
            call(service, instance, "add", new Class<?>[]{int.class, int.class}, 1, 2);
            fail("the second call within the burst must be rejected");
        } catch (JQuickRejectedException rejection) {
            assertEquals("governance.rate-limit", rejection.getPolicy());
            assertTrue(rejection.getMessage().contains(SERVICE));
        }

        String scrape = harness.environment().getMeters().scrape();
        assertTrue("a rejection is a metric, not just a log line",
                scrape.contains("jquick_governance_rejections_total"));
        assertTrue(scrape.contains("policy=\"governance.rate-limit\""));
    }

    /**
     * A blacklisted operation is refused unconditionally, and it is the first check — so a blacklisted
     * call is rejected even when the limiter would have allowed it.
     */
    @Test
    public void blacklistedOperationIsRejectedImmediately() throws Exception {
        JQuickHarness harness = JQuickHarness.ofPlugins(
                RULES + ";governance.enabled=true;governance.blacklist=" + SERVICE + "#boom",
                new JQuickTracePlugin(), new JQuickGovernancePlugin());
        Class<?> service = harness.load(SERVICE);
        Object instance = service.getDeclaredConstructor().newInstance();

        try {
            call(service, instance, "boom", new Class<?>[]{int.class}, 5);
            fail("a blacklisted operation must not run");
        } catch (JQuickRejectedException rejection) {
            assertEquals("governance.blacklist", rejection.getPolicy());
        }
        // Everything else still works.
        assertEquals(2, call(service, instance, "add", new Class<?>[]{int.class, int.class}, 1, 1));
    }

    /**
     * A circuit must actually stop calls once the failure rate is exceeded end to end, and count the
     * outcome of the calls it lets through.
     */
    @Test
    public void circuitOpensAfterRepeatedFailuresAndRejectsTheCall() throws Exception {
        JQuickHarness harness = JQuickHarness.ofPlugins(
                RULES + ";governance.enabled=true;circuit.failure.rate=0.5"
                        + ";circuit.window=4;circuit.minimum.samples=2;circuit.open.ms=60000",
                new JQuickTracePlugin(), new JQuickGovernancePlugin());
        Class<?> service = harness.load(SERVICE);
        Object instance = service.getDeclaredConstructor().newInstance();
        Method boom = service.getDeclaredMethod("boom", int.class);

        for (int i = 0; i < 2; i++) {
            try {
                boom.invoke(instance, 0);
                fail("the fixture should have thrown");
            } catch (InvocationTargetException expected) {
                // The breaker observes the failure through the span, not through the exception.
            }
        }

        try {
            call(service, instance, "boom", new Class<?>[]{int.class}, 1);
            fail("the open circuit must refuse the call");
        } catch (JQuickRejectedException rejection) {
            assertEquals("governance.circuit-breaker", rejection.getPolicy());
        }

        String scrape = harness.environment().getMeters().scrape();
        assertTrue("the breaker's verdict must be visible as a metric: " + scrape,
                scrape.contains("jquick_governance_rejections_total{policy=\"governance.circuit-breaker\"}"));
        assertTrue("the real failures must be counted as errors",
                sampleValue(scrape, "jquick_governance_calls_total", "outcome=\"error\"") >= 2);
    }

    /**
     * A rejected call must not itself be fed back into the breaker. If it were, every rejection would
     * count as a failure, the failure rate would stay at 100% and the circuit could never close again.
     */
    @Test
    public void rejectionDoesNotFeedTheBreaker() throws Exception {
        JQuickHarness harness = JQuickHarness.ofPlugins(
                RULES + ";governance.enabled=true;circuit.failure.rate=0.5"
                        + ";circuit.window=4;circuit.minimum.samples=2;circuit.open.ms=60000",
                new JQuickTracePlugin(), new JQuickGovernancePlugin());
        Class<?> service = harness.load(SERVICE);
        Object instance = service.getDeclaredConstructor().newInstance();
        Method boom = service.getDeclaredMethod("boom", int.class);

        for (int i = 0; i < 2; i++) {
            try {
                boom.invoke(instance, 0);
            } catch (InvocationTargetException expected) {
                // Counted as a failure.
            }
        }
        int rejections = 0;
        for (int i = 0; i < 3; i++) {
            try {
                call(service, instance, "boom", new Class<?>[]{int.class}, 1);
            } catch (JQuickRejectedException expected) {
                rejections++;
            }
        }
        assertEquals("the open circuit must have refused all three calls", 3, rejections);

        String scrape = harness.environment().getMeters().scrape();
        assertEquals("only the two real failures may be counted as errors", 2L,
                sampleValue(scrape, "jquick_governance_calls_total", "outcome=\"error\""));
    }

    /* ================================================================== helpers */

    private static long sampleValue(String scrape, String metricName, String labelSubstring) {
        for (String line : scrape.split("\n")) {
            if (!line.startsWith(metricName + "{")) {
                continue;
            }
            if (labelSubstring != null && line.indexOf(labelSubstring) < 0) {
                continue;
            }
            return Long.parseLong(line.substring(line.lastIndexOf(' ') + 1).trim());
        }
        return -1L;
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
