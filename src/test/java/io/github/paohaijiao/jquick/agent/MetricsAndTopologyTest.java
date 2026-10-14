package io.github.paohaijiao.jquick.agent;

import io.github.paohaijiao.jquick.agent.metrics.JQuickCounter;
import io.github.paohaijiao.jquick.agent.metrics.JQuickGauge;
import io.github.paohaijiao.jquick.agent.metrics.JQuickMeterRegistry;
import io.github.paohaijiao.jquick.agent.metrics.JQuickTimer;
import io.github.paohaijiao.jquick.agent.plugin.metrics.JQuickMetricsPlugin;
import io.github.paohaijiao.jquick.agent.plugin.trace.JQuickTracePlugin;
import io.github.paohaijiao.jquick.agent.test.JQuickHarness;
import io.github.paohaijiao.jquick.agent.topology.JQuickTopology;
import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests for the metrics and topology capabilities: the format Prometheus will actually read, and the
 * aggregation behind the call graph.
 *
 * @author Martin
 */
public class MetricsAndTopologyTest {

    private static final String SERVICE = "example.app.FixtureService";

    private static final String RULES = "includes=example.app.*;quiet=true";

    /* ================================================================== end to end */

    /**
     * The metrics plugin injects no byte-code at all — it consumes the span stream — so this test
     * also proves that the listener abstraction is enough to add a whole backend.
     */
    @Test
    public void metricsPluginPublishesCallsAndErrors() throws Exception {
        JQuickHarness harness = JQuickHarness.ofPlugins(RULES,
                new JQuickTracePlugin(), new JQuickMetricsPlugin());
        Class<?> service = harness.load(SERVICE);
        Object instance = service.getDeclaredConstructor().newInstance();

        call(service, instance, "add", new Class<?>[]{int.class, int.class}, 1, 2);
        call(service, instance, "add", new Class<?>[]{int.class, int.class}, 3, 4);
        call(service, instance, "add", new Class<?>[]{int.class, int.class}, 5, 6);
        try {
            call(service, instance, "boom", new Class<?>[]{int.class}, 0);
            fail("the fixture should have thrown");
        } catch (IllegalStateException expected) {
            // The point is the metric, not the exception.
        }

        String scrape = harness.environment().getMeters().scrape();

        // The default label set is class-level by design (see defaultLabelsStayLowCardinality), so
        // the three successful calls and the failing one collapse onto the same series: four calls,
        // one of which failed. The error counter is what separates them.
        long calls = sampleValue(scrape, "jquick_method_calls_total", SERVICE);
        long errors = sampleValue(scrape, "jquick_method_errors_total", SERVICE);
        assertEquals("every invocation must be counted, including the failing one", 4, calls);
        assertEquals("exactly one invocation failed", 1, errors);
        assertTrue("latency must be observed", scrape.contains("jquick_method_duration_seconds_count"));
        assertTrue("latency must carry a sum", scrape.contains("jquick_method_duration_seconds_sum"));
    }

    /**
     * The class-level default trades method granularity for cardinality; an operator who wants the
     * finer view opts in with one key, and the two label modes must not be confused for one another.
     */
    @Test
    public void perMethodLabelsSeparateTheFailingCall() throws Exception {
        JQuickHarness harness = JQuickHarness.ofPlugins(RULES + ";metrics.labels=method",
                new JQuickTracePlugin(), new JQuickMetricsPlugin());
        Class<?> service = harness.load(SERVICE);
        Object instance = service.getDeclaredConstructor().newInstance();

        call(service, instance, "add", new Class<?>[]{int.class, int.class}, 1, 2);
        call(service, instance, "add", new Class<?>[]{int.class, int.class}, 3, 4);
        call(service, instance, "add", new Class<?>[]{int.class, int.class}, 5, 6);
        try {
            call(service, instance, "boom", new Class<?>[]{int.class}, 0);
            fail("the fixture should have thrown");
        } catch (IllegalStateException expected) {
            // The point is the metric, not the exception.
        }

        String scrape = harness.environment().getMeters().scrape();
        assertTrue("the operation must name the method: " + scrape,
                scrape.contains("target=\"" + SERVICE + "#add"));
        assertEquals("the three successful calls share one series", 3,
                sampleValue(scrape, "jquick_method_calls_total", SERVICE + "#add"));
        assertEquals("the failing method gets its own series", 1,
                sampleValue(scrape, "jquick_method_calls_total", SERVICE + "#boom"));
        assertEquals("and only it is an error", 1,
                sampleValue(scrape, "jquick_method_errors_total", SERVICE + "#boom"));
    }

    /**
     * A slow call is a call over the configured threshold; the counter exists so an alert can be
     * written against the ratio rather than against a latency histogram nobody configured.
     */
    @Test
    public void slowCallsAreCountedAgainstTheConfiguredThreshold() throws Exception {
        JQuickHarness harness = JQuickHarness.ofPlugins(RULES + ";trace.slow.ms=0",
                new JQuickTracePlugin(), new JQuickMetricsPlugin());
        Class<?> service = harness.load(SERVICE);
        Object instance = service.getDeclaredConstructor().newInstance();
        call(service, instance, "add", new Class<?>[]{int.class, int.class}, 1, 1);

        String scrape = harness.environment().getMeters().scrape();
        assertTrue("with a zero threshold every call is slow",
                sampleValue(scrape, "jquick_method_slow_calls_total", SERVICE) >= 1);
    }

    /**
     * Metrics must not be labelled by method signature by default: a mid-sized codebase produces tens
     * of thousands of signatures, and each one would become a separate Prometheus time series.
     */
    @Test
    public void defaultLabelsStayLowCardinality() throws Exception {
        JQuickHarness harness = JQuickHarness.ofPlugins(RULES,
                new JQuickTracePlugin(), new JQuickMetricsPlugin());
        Class<?> service = harness.load(SERVICE);
        Object instance = service.getDeclaredConstructor().newInstance();
        call(service, instance, "add", new Class<?>[]{int.class, int.class}, 1, 1);
        call(service, instance, "greet", new Class<?>[]{String.class}, "x");

        String scrape = harness.environment().getMeters().scrape();
        assertTrue("labels must name the class: " + scrape,
                scrape.contains("target=\"" + SERVICE + "\""));
        assertFalse("labels must not carry method signatures",
                scrape.contains("target=\"" + SERVICE + "#"));
    }

    /* ================================================================== exposition format */

    /**
     * The exposition format is a contract with a scraper, not a log line: a missing {@code # TYPE},
     * a duplicated {@code # HELP} or an unescaped quote silently breaks the whole scrape job.
     */
    @Test
    public void prometheusLayoutIsWellFormed() {
        JQuickMeterRegistry registry = new JQuickMeterRegistry();

        JQuickCounter counter = registry.counter("jquick_test_total", "path", "a\"b\\c");
        counter.increment();
        counter.add(4);

        JQuickTimer timer = registry.timer("jquick_test_seconds", "kind", "unit");
        timer.record(1_000_000L);
        timer.record(3_000_000L);

        JQuickGauge gauge = registry.gauge("jquick_test_open", new JQuickGauge.ValueSource() {
            @Override
            public double read() {
                return 7d;
            }
        });

        String scrape = registry.scrape();

        assertEquals("HELP must appear exactly once per metric name",
                1, occurrences(scrape, "# HELP jquick_test_total "));
        assertEquals("TYPE must appear exactly once per metric name",
                1, occurrences(scrape, "# TYPE jquick_test_total counter"));
        assertTrue("a quote inside a label value must be escaped",
                scrape.contains("path=\"a\\\"b\\\\c\""));
        assertTrue(scrape.contains("jquick_test_total{path=\"a\\\"b\\\\c\"} 5"));

        assertTrue(scrape.contains("# TYPE jquick_test_seconds summary"));
        assertTrue(scrape.contains("jquick_test_seconds_count{kind=\"unit\"} 2"));
        assertTrue("the sum must be in seconds", scrape.contains("jquick_test_seconds_sum{kind=\"unit\"} 0.004"));
        assertTrue(scrape.contains("jquick_test_seconds_max{kind=\"unit\"} 0.003"));
        assertEquals("a repeated name must not repeat its header",
                1, occurrences(scrape, "# TYPE jquick_test_seconds "));

        assertTrue(scrape.contains("# TYPE jquick_test_open gauge"));
        assertTrue(scrape.contains("jquick_test_open 7"));
        assertEquals(7d, gauge.value(), 0.0001d);
        assertNotNull(counter);
    }

    /**
     * A metric name that is not a legal Prometheus identifier must be repaired rather than emitted and
     * rejected by the scraper.
     */
    @Test
    public void illegalMetricNamesAreSanitized() {
        JQuickMeterRegistry registry = new JQuickMeterRegistry();
        registry.counter("jquick.agent bad name").increment();
        registry.counter("1st_metric").increment();
        String scrape = registry.scrape();
        assertTrue("dots and spaces must be replaced: " + scrape,
                scrape.contains("jquick_agent_bad_name 1"));
        assertTrue("a leading digit must be prefixed: " + scrape, scrape.contains("_1st_metric 1"));
    }

    @Test
    public void registryAggregatesByNameAndLabels() {
        JQuickMeterRegistry registry = new JQuickMeterRegistry();
        registry.counter("jquick_a_total", "x", "1").increment();
        registry.counter("jquick_a_total", "x", "1").increment();
        registry.counter("jquick_a_total", "x", "2").increment();
        assertEquals("two label sets, three series in total", 2, registry.size());
        String scrape = registry.scrape();
        assertTrue(scrape.contains("jquick_a_total{x=\"1\"} 2"));
        assertTrue(scrape.contains("jquick_a_total{x=\"2\"} 1"));
    }

    /* ================================================================== topology */

    /**
     * The call graph is built from outgoing spans only — an inbound edge has no known caller inside
     * the process, so folding it in would invent a node.
     */
    @Test
    public void topologyAggregatesEdges() {
        JQuickTopology topology = new JQuickTopology();
        topology.record("example.app.OrderService#create(long)",
                "example.app.OrderDao#insert(Order)", "jdbc", 4_000_000L, true);
        topology.record("example.app.OrderService#create(long)",
                "example.app.OrderDao#insert(Order)", "jdbc", 6_000_000L, true);
        topology.record("example.app.OrderService#create(long)",
                "example.app.OrderDao#insert(Order)", "jdbc", 10_000_000L, false);

        assertEquals(1, topology.size());
        JQuickTopology.Edge edge = topology.edges().get(0);
        assertEquals("example.app.OrderService#create(long)", edge.getCaller());
        assertEquals("example.app.OrderDao#insert(Order)", edge.getCallee());
        assertEquals("jdbc", edge.getComponent());
        assertEquals(3, edge.getCalls());
        assertEquals(1, edge.getErrors());
        assertEquals(6, edge.averageMillis());
        assertEquals(10, edge.maxMillis());

        String line = edge.toPrometheusLine();
        assertTrue(line.contains("jquick_topology_calls_total"));
        assertTrue(line.contains("caller=\"example.app.OrderService#create(long)\""));
        assertTrue(line.contains("component=\"jdbc\""));
        assertTrue(line.endsWith(" 3"));
    }

    /**
     * A caller that could not be determined must be labelled as such, not silently attributed to the
     * callee — a self-loop would be worse than a missing node.
     */
    @Test
    public void unknownCallersAreLabelledNotInvented() {
        JQuickTopology topology = new JQuickTopology();
        topology.record(null, "example.app.OrderDao#insert(Order)", "jdbc", 1_000_000L, true);
        JQuickTopology.Edge edge = topology.edges().get(0);
        assertEquals("(uninstrumented)", edge.getCaller());
        assertTrue(topology.render().contains("(uninstrumented)"));
    }

    /* ================================================================== helpers */

    /**
     * Reads a counter value out of a scrape, matching on a substring of the labels. Returns {@code -1}
     * when no such series exists, so a missing metric fails the assertion rather than the parse.
     */
    private static long sampleValue(String scrape, String metricName, String labelSubstring) {
        for (String line : scrape.split("\n")) {
            if (!line.startsWith(metricName + "{")) {
                continue;
            }
            if (labelSubstring != null && line.indexOf(labelSubstring) < 0) {
                continue;
            }
            int space = line.lastIndexOf(' ');
            return Long.parseLong(line.substring(space + 1).trim());
        }
        return -1L;
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
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
