package io.github.paohaijiao.jquick.agent;

import io.github.paohaijiao.jquick.agent.plugin.coverage.JQuickCoverage;
import io.github.paohaijiao.jquick.agent.plugin.coverage.JQuickCoveragePlugin;
import io.github.paohaijiao.jquick.agent.test.JQuickHarness;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for method-level execution coverage.
 *
 * <p>The plugin registers itself in a static field on purpose — the agent lives outside the
 * application's object graph, so a report has to be reachable without a reference to the plugin. That
 * makes the field process-wide state, and these tests clear it the same way they clear the
 * environment: through reflection, because a test that depends on another test's leftovers is not a
 * test.
 *
 * <p>What is asserted is the pair of halves that make a coverage number meaningful: what was
 * instrumented, and what actually ran. Asserting only the executed half would report 100% coverage for
 * a suite that happened to touch one method.
 *
 * @author Martin
 */
public class CoverageTest {

    private static final String SERVICE = "example.app.FixtureService";

    private static final String RULES = "includes=example.app.*;quiet=true"
            + ";coverage.enabled=true;coverage.includes=example.app.*";

    @Before
    public void clearStaticInstance() throws Exception {
        setInstance(null);
    }

    @After
    public void tearDown() throws Exception {
        setInstance(null);
    }

    /* ================================================================== aggregation */

    /**
     * The report groups by class and computes the ratio, so the numbers an operator reads are the
     * ratio of what ran to what was instrumented — not the ratio of what ran to what ran.
     */
    @Test
    public void theReportComputesCoverageAgainstWhatWasInstrumented() {
        JQuickCoverage coverage = new JQuickCoverage();
        coverage.register("com.acme.OrderService#create(long)");
        coverage.register("com.acme.OrderService#cancel(long)");
        coverage.register("com.acme.OrderService#refund(long)");
        coverage.register("com.acme.PaymentService#pay(java.lang.String)");

        coverage.record("com.acme.OrderService#create(long)");
        coverage.record("com.acme.PaymentService#pay(java.lang.String)");

        assertEquals(2, coverage.executedCount());
        assertEquals(4, coverage.instrumentedCount());
        assertEquals("the two methods that never ran are what the report is for",
                "[com.acme.OrderService#cancel(long), com.acme.OrderService#refund(long)]",
                coverage.untouchedOperations().toString());

        String report = coverage.report();
        assertTrue(report.startsWith("jquick-agent method coverage"));
        assertTrue("one line per class: " + report, report.contains("com.acme.OrderService"));
        assertTrue(report.contains("com.acme.PaymentService"));
        assertTrue("the per-class ratio: " + report, report.contains("33.33%"));
        assertTrue("the payment service is fully covered: " + report, report.contains("100.00%"));
        assertTrue("a total is printed: " + report, report.contains("TOTAL (50.00%)"));
    }

    /**
     * A method that ran without having been declared instrumented must not be dropped from the total:
     * the executed set is authoritative about what happened, even when the instrumented set is
     * incomplete (a class instrumented before the plugin initialised, for example).
     */
    @Test
    public void anExecutedMethodIsCountedEvenWhenItWasNeverRegistered() {
        JQuickCoverage coverage = new JQuickCoverage();
        coverage.register("com.acme.A#x()");
        coverage.record("com.acme.A#x()");
        coverage.record("com.acme.B#y()");

        assertEquals("both observed calls count towards the total", 2, coverage.executedCount());
        assertTrue(coverage.untouchedOperations().isEmpty());
        assertTrue("the unregistered class still gets a line: " + coverage.report(),
                coverage.report().contains("com.acme.B"));
        assertTrue(coverage.report().contains("TOTAL (100.00%)"));
    }

    @Test
    public void anEmptyCoverageRegistryReportsZeroWithoutFailing() {
        String report = new JQuickCoverage().report();
        assertTrue(report.contains("TOTAL (0.00%)"));
    }

    /**
     * A build archives the report next to its test results, so writing it must create the parent
     * directory and persist exactly the text that was rendered.
     */
    @Test
    public void theReportCanBeWrittenToDisk() throws Exception {
        JQuickCoverage coverage = new JQuickCoverage();
        coverage.register("com.acme.A#x()");
        coverage.record("com.acme.A#x()");

        File target = new File("target/tmp-coverage-" + System.nanoTime() + "/nested/coverage.txt");
        coverage.writeTo(target);
        assertTrue("the parent directory must be created", target.isFile());

        String expected = coverage.report().replace("\r\n", "\n");
        assertEquals("the file must hold the rendered report", expected, readFile(target));

        coverage.writeTo(null);
    }

    /* ================================================================== end to end */

    /**
     * The capability is self-contained: it brings its own enhancer and its own listener, so it works
     * with the tracer switched off entirely. That is the single-responsibility property, observed
     * rather than asserted.
     */
    @Test
    public void coverageWorksWithoutTheTracerBeingEnabled() throws Exception {
        JQuickHarness harness = JQuickHarness.ofPlugins(RULES, new JQuickCoveragePlugin());
        Class<?> service = harness.load(SERVICE);
        Object instance = service.getDeclaredConstructor().newInstance();

        call(service, instance, "add", new Class<?>[]{int.class, int.class}, 1, 2);
        call(service, instance, "touch", new Class<?>[]{});

        JQuickCoverage coverage = JQuickCoveragePlugin.getInstance();
        assertNotNull("the plugin must publish its registry", coverage);
        assertEquals("the two calls that ran must be recorded", 2, coverage.executedCount());
        assertTrue(coverage.wasExecuted(SERVICE + "#add(int, int)"));
        assertTrue(coverage.wasExecuted(SERVICE + "#touch()"));
    }

    /**
     * The reconciliation between the two halves: the instrumented set is rebuilt from the method
     * registry, because at initialisation time no class has been rewritten yet. Without it the report
     * would claim every executed method was also instrumented, which is 100% coverage by construction.
     */
    @Test
    public void theReportReconcilesTheInstrumentedSetFromTheRegistry() throws Exception {
        JQuickHarness harness = JQuickHarness.ofPlugins(RULES, new JQuickCoveragePlugin());
        Class<?> service = harness.load(SERVICE);
        Object instance = service.getDeclaredConstructor().newInstance();
        call(service, instance, "add", new Class<?>[]{int.class, int.class}, 1, 2);

        String report = JQuickCoveragePlugin.report();
        JQuickCoverage coverage = JQuickCoveragePlugin.getInstance();

        assertTrue("the enhancer must have registered its targets in the registry",
                coverage.instrumentedCount() > 1);
        assertEquals("only one method ran", 1, coverage.executedCount());
        assertTrue("the methods that never ran are the finding: " + coverage.untouchedOperations(),
                coverage.untouchedOperations().contains(SERVICE + "#greet(java.lang.String)"));
        assertFalse("a method that ran is not untouched",
                coverage.untouchedOperations().contains(SERVICE + "#add(int, int)"));

        assertTrue("the report must name the class: " + report, report.contains(SERVICE));
        assertTrue("and admit a partial result: " + report, report.contains("TOTAL ("));
        assertFalse("a partial result must not read as 100%: " + report,
                report.contains("TOTAL (100.00%)"));
    }

    /**
     * A class the include rule does not select is neither instrumented nor measured — the report must
     * not imply that unmeasured code is untested code.
     */
    @Test
    public void classesOutsideTheIncludeRuleAreNotMeasured() throws Exception {
        JQuickHarness harness = JQuickHarness.ofPlugins(
                "includes=example.app.FixtureService;quiet=true"
                        + ";coverage.enabled=true;coverage.includes=example.app.FixtureService",
                new JQuickCoveragePlugin());
        Class<?> service = harness.load(SERVICE);
        Object instance = service.getDeclaredConstructor().newInstance();
        call(service, instance, "add", new Class<?>[]{int.class, int.class}, 1, 2);

        String report = JQuickCoveragePlugin.report();
        assertFalse("an unselected class must not appear: " + report,
                report.contains("example.app.FixtureIgnored"));
        assertTrue(report.contains(SERVICE));
    }

    /**
     * Without the capability there is no report, and the caller is told why rather than handed an
     * empty one that reads like a passing build.
     */
    @Test
    public void requestingTheReportWithoutTheCapabilityExplainsItself() {
        String report = JQuickCoveragePlugin.report();
        assertTrue(report.contains("coverage is not active"));
        assertTrue(report.contains("coverage.enabled=true"));
    }

    /* ================================================================== helpers */

    /**
     * Clears the plugin's process-wide registry, mirroring how {@link JQuickHarness} clears the
     * environment singleton.
     */
    private static void setInstance(JQuickCoverage value) throws Exception {
        Field field = JQuickCoveragePlugin.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, value);
    }

    /**
     * Reads a text file with the platform line separator normalised to {@code \n}, so the comparison
     * is about the content rather than about the host operating system.
     */
    private static String readFile(File file) throws Exception {
        java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                new java.io.FileInputStream(file), "UTF-8"));
        try {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } finally {
            reader.close();
        }
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
