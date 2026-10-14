package io.github.paohaijiao.jquick.agent;

import io.github.paohaijiao.jquick.agent.core.JQuickEnvironment;
import io.github.paohaijiao.jquick.agent.core.JQuickRejectedException;
import io.github.paohaijiao.jquick.agent.plugin.classload.JQuickClassLoadGuard;
import io.github.paohaijiao.jquick.agent.plugin.classload.JQuickClassLoadPlugin;
import io.github.paohaijiao.jquick.agent.plugin.classload.JQuickJarConflictDetector;
import io.github.paohaijiao.jquick.agent.plugin.thread.JQuickThreadMonitor;
import io.github.paohaijiao.jquick.agent.plugin.thread.JQuickThreadPlugin;
import io.github.paohaijiao.jquick.agent.plugin.thread.JQuickUncaughtExceptionHandler;
import io.github.paohaijiao.jquick.agent.plugin.trace.JQuickTracePlugin;
import io.github.paohaijiao.jquick.agent.test.JQuickHarness;
import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests for the capabilities that guard or observe the JVM itself: uncaught exceptions, the thread
 * census and the class-loading sandbox.
 *
 * <p>These are the capabilities with the widest blast radius — one installs a process-wide default
 * exception handler, another refuses to execute code — so each is asserted twice: once on its own, and
 * once for the property that keeps it from being a hazard. An uncaught-exception handler that
 * <em>replaces</em> the framework's own is worse than no handler at all, and a sandbox rule that leaks
 * into the next test run is a self-inflicted outage in the build.
 *
 * @author Martin
 */
public class RuntimeGuardTest {

    private static final String SERVICE = "example.app.FixtureService";

    private static final String RULES = "includes=example.app.*;quiet=true";

    private Thread.UncaughtExceptionHandler handlerBefore;

    @After
    public void tearDown() {
        if (handlerBefore != null) {
            Thread.setDefaultUncaughtExceptionHandler(handlerBefore);
        }
        // The guard's rule set is static, by design: it must be reachable from injected byte-code.
        // Leaving rules behind would make the next suite observe a sandbox nobody configured.
        JQuickClassLoadGuard.configure(null);
    }

    /* ================================================================== uncaught exceptions */

    /**
     * The handler is additive. A framework that installs its own handler — most servlet containers and
     * schedulers do — must keep working, so the previous handler is preserved and still invoked.
     */
    @Test
    public void theHandlerRunsInAdditionToThePreviousOne() {
        final AtomicInteger previousCalls = new AtomicInteger();
        Thread.UncaughtExceptionHandler previous = new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable error) {
                previousCalls.incrementAndGet();
            }
        };
        JQuickEnvironment environment = freshEnvironment();
        JQuickUncaughtExceptionHandler handler =
                new JQuickUncaughtExceptionHandler(previous, environment);

        handler.uncaughtException(new Thread("worker-1"), new IllegalStateException("died"));
        handler.uncaughtException(new Thread("worker-1"), new IllegalArgumentException("died again"));

        assertSame(previous, handler.getPrevious());
        assertEquals("the previous handler must still run for every exception",
                2, previousCalls.get());
        assertEquals("and every exception must be counted",
                2L, sampleValue(environment.getMeters().scrape(),
                        "jquick_uncaught_exceptions_total", "thread=\"worker-1\""));
    }

    /**
     * A dying thread must not be delayed by a broken observer, so every failure inside the handler —
     * including one raised by the previous handler — is contained.
     */
    @Test
    public void aBrokenPreviousHandlerCannotBreakTheObserver() {
        Thread.UncaughtExceptionHandler broken = new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable error) {
                throw new IllegalStateException("the previous handler is broken too");
            }
        };
        JQuickEnvironment environment = freshEnvironment();
        JQuickUncaughtExceptionHandler handler =
                new JQuickUncaughtExceptionHandler(broken, environment);

        handler.uncaughtException(new Thread("worker-2"), new IllegalStateException("died"));

        assertEquals("the exception must still be recorded",
                1L, sampleValue(environment.getMeters().scrape(),
                        "jquick_uncaught_exceptions_total", "thread=\"worker-2\""));
    }

    @Test
    public void theHandlerToleratesMissingContext() {
        JQuickUncaughtExceptionHandler handler =
                new JQuickUncaughtExceptionHandler(null, null);
        assertNull(handler.getPrevious());
        // A null thread, a null throwable and no environment must all be survivable.
        handler.uncaughtException(null, null);
        handler.uncaughtException(new Thread("x"), new RuntimeException("no stack"));
    }

    /**
     * End to end: the plugin installs the handler, the JVM invokes it for a thread that dies
     * unhandled, and the failure becomes a counter. This is the "hardest failure to diagnose in
     * production" case, observed rather than described.
     */
    @Test
    public void aThreadThatDiesUnhandledIsRecorded() throws Exception {
        handlerBefore = Thread.getDefaultUncaughtExceptionHandler();

        JQuickHarness harness = JQuickHarness.ofPlugins(
                RULES + ";thread.enabled=true;thread.pool.enabled=false",
                new JQuickThreadPlugin());

        Thread.UncaughtExceptionHandler installed = Thread.getDefaultUncaughtExceptionHandler();
        assertTrue("the plugin must install its handler, found: " + installed,
                installed instanceof JQuickUncaughtExceptionHandler);
        assertSame("and must preserve what was there before",
                handlerBefore, ((JQuickUncaughtExceptionHandler) installed).getPrevious());

        final CountDownLatch died = new CountDownLatch(1);
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    throw new IllegalStateException("unhandled in a worker");
                } finally {
                    died.countDown();
                }
            }
        }, "jquick-test-worker");
        worker.start();
        assertTrue("the worker must finish", died.await(5, TimeUnit.SECONDS));
        worker.join(5000L);

        assertEquals("the death must have reached the installed handler",
                1L, sampleValue(harness.environment().getMeters().scrape(),
                        "jquick_uncaught_exceptions_total", "thread=\"jquick-test-worker\""));
    }

    /* ================================================================== thread census */

    @Test
    public void theThreadCensusGroupsByRoleAndFindsALeak() throws Exception {
        Map<String, Integer> before = JQuickThreadMonitor.snapshot();
        assertTrue("the census must see the running JVM", before.size() > 0);
        assertEquals("the total must agree with the snapshot",
                JQuickThreadMonitor.count(), total(before));
        assertTrue("the census must stay sorted", sorted(before));

        // Five threads sharing a role, named the way a pool names its workers.
        Thread[] pool = new Thread[5];
        for (int i = 0; i < pool.length; i++) {
            pool[i] = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(30_000L);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }
            }, "jquick-role-probe-" + i);
            pool[i].setDaemon(true);
            pool[i].start();
        }
        try {
            Map<String, Integer> after = JQuickThreadMonitor.snapshot();
            assertEquals("the trailing discriminator is what turns five names into one role",
                    Integer.valueOf(5), after.get("jquick-role-probe"));

            List<String> leaks = JQuickThreadMonitor.findLeaks(before, 2);
            assertFalse("a role that grew past the limit must be reported: " + leaks, leaks.isEmpty());
            boolean reported = false;
            for (String leak : leaks) {
                if (leak.contains("jquick-role-probe")) {
                    reported = true;
                    assertTrue("the finding must give the count and the limit: " + leak,
                            leak.contains("5") && leak.contains("limit 2"));
                }
            }
            assertTrue("the leaky role must be named: " + leaks, reported);

            // The inventory is the other half of the census: it names the threads, and the frame limit
            // is what keeps a 5000-thread dump from being unreadable.
            assertFalse("a zero frame limit must print no frames",
                    JQuickThreadMonitor.report(0).contains("\n    at "));

            String report = JQuickThreadMonitor.report(1);
            assertTrue("the inventory names the threads: " + report,
                    report.contains("jquick-role-probe-0"));
            int threadLines = 0;
            int frameLines = 0;
            for (String line : report.split("\n")) {
                if (line.startsWith("    at ")) {
                    frameLines++;
                } else if (!line.isEmpty()) {
                    threadLines++;
                }
            }
            assertTrue("the inventory must list threads: " + threadLines, threadLines > 0);
            assertTrue("no thread may print more frames than requested: frames=" + frameLines
                    + ", threads=" + threadLines, frameLines <= threadLines);

            assertEquals("a limit caps the number of roles returned",
                    1, JQuickThreadMonitor.topRoles(1).size());
            assertTrue("a baseline of null must not throw",
                    JQuickThreadMonitor.findLeaks(null, Integer.MAX_VALUE).isEmpty());
        } finally {
            for (Thread thread : pool) {
                thread.interrupt();
            }
            for (Thread thread : pool) {
                thread.join(5000L);
            }
        }
    }

    /**
     * The census must be reachable from a running agent, not merely a library nobody calls: the plugin
     * registers the live thread count as a gauge, which is what turns a leak into a rising line.
     */
    @Test
    public void thePluginPublishesTheThreadCountAsAGauge() {
        JQuickHarness harness = JQuickHarness.ofPlugins(
                RULES + ";thread.enabled=true;thread.pool.enabled=false;thread.uncaught.enabled=false",
                new JQuickThreadPlugin());

        String scrape = harness.environment().getMeters().scrape();
        assertTrue("the thread count must be exposed: " + scrape, scrape.contains("jquick_threads "));
        assertTrue("# TYPE must be declared: " + scrape,
                scrape.contains("# TYPE jquick_threads gauge"));
        assertTrue("the census entry point must work", JQuickThreadPlugin.census(2).length() > 0);
    }

    /* ================================================================== sandbox blacklist */

    /**
     * The assertion form is the reliable enforcement point: a class name arriving from an untrusted
     * source — a deserialisation allow list, a plugin loader — is refused with a policy and a subject,
     * so the caller can report it rather than merely crash.
     */
    @Test
    public void theSandboxAssertionNamesThePolicyAndTheClass() {
        JQuickClassLoadGuard.configure(Arrays.asList("com.acme.internal.*", "com.acme.Secret"));

        assertTrue(JQuickClassLoadGuard.isBlacklisted("com.acme.internal.Token"));
        assertTrue("an exact rule matches a nested name too",
                JQuickClassLoadGuard.isBlacklisted("com.acme.Secret.Holder"));
        assertFalse(JQuickClassLoadGuard.isBlacklisted("com.acme.publicapi.Order"));
        assertFalse("with no rule set nothing is refused",
                JQuickClassLoadGuard.isBlacklisted(null));

        try {
            JQuickClassLoadGuard.assertAllowed("com.acme.internal.Token");
            fail("a blacklisted class must be refused");
        } catch (JQuickRejectedException rejection) {
            assertEquals("sandbox.blacklist", rejection.getPolicy());
            assertTrue(rejection.getMessage().contains("com.acme.internal.Token"));
        }

        assertEquals(2, JQuickClassLoadGuard.blacklist().size());
        JQuickClassLoadGuard.configure(null);
        assertTrue(JQuickClassLoadGuard.blacklist().isEmpty());
        JQuickClassLoadGuard.assertAllowed("com.acme.internal.Token");
    }

    /**
     * End to end: a blacklisted operation is refused before its body runs, and everything outside the
     * rule still works. The sandbox is class-scoped by design — the guard sees a class name, not a
     * call site — and that is what the test documents.
     */
    @Test
    public void theSandboxGateRefusesAnInstrumentedCall() throws Exception {
        JQuickHarness harness = JQuickHarness.ofPlugins(
                RULES + ";classload.enabled=true;classload.blacklist=" + SERVICE,
                new JQuickTracePlugin(), new JQuickClassLoadPlugin());
        Class<?> service = harness.load(SERVICE);

        try {
            call(service, service.getDeclaredConstructor().newInstance(),
                    "add", new Class<?>[]{int.class, int.class}, 1, 2);
            fail("a call on a blacklisted class must be refused");
        } catch (JQuickRejectedException rejection) {
            assertEquals("sandbox.blacklist", rejection.getPolicy());
            assertTrue(rejection.getMessage().contains(SERVICE));
        }

        // A class outside the rule is unaffected.
        JQuickHarness clean = JQuickHarness.ofPlugins(
                RULES + ";classload.enabled=true;classload.blacklist=com.other.*",
                new JQuickTracePlugin(), new JQuickClassLoadPlugin());
        Class<?> allowed = clean.load(SERVICE);
        assertEquals(3, call(allowed, allowed.getDeclaredConstructor().newInstance(),
                "add", new Class<?>[]{int.class, int.class}, 1, 2));
    }

    /**
     * Enabling the plugin with no blacklist must install no rules at all: an empty rule set that still
     * activates enforcement machinery is a mechanism with no purpose, and the first bug in it would
     * refuse calls nobody asked to refuse.
     */
    @Test
    public void anEmptyBlacklistRefusesNothing() throws Exception {
        JQuickHarness harness = JQuickHarness.ofPlugins(
                RULES + ";classload.enabled=true",
                new JQuickTracePlugin(), new JQuickClassLoadPlugin());
        assertTrue("an absent rule set must leave the guard empty",
                JQuickClassLoadGuard.blacklist().isEmpty());

        Class<?> service = harness.load(SERVICE);
        assertEquals("and nothing may be refused", 3,
                call(service, service.getDeclaredConstructor().newInstance(),
                        "add", new Class<?>[]{int.class, int.class}, 1, 2));
    }

    /* ================================================================== classpath conflicts */

    /**
     * Two classpath entries providing the same class is the classic cause of "it works on my machine":
     * the wrong copy wins and the symptom appears layers away. The scan is cheap enough to run once at
     * startup, so it is asserted against a synthetic classpath rather than hoped for.
     */
    @Test
    public void duplicateClassesOnTheClasspathAreFound() throws Exception {
        String original = System.getProperty("java.class.path");
        File first = directoryWithDuplicateClass("first");
        File second = directoryWithDuplicateClass("second");
        try {
            System.setProperty("java.class.path", first.getPath() + File.pathSeparator
                    + second.getPath());
            Map<String, List<String>> conflicts = JQuickJarConflictDetector.conflicts();
            assertEquals("one duplicated class: " + conflicts, 1, conflicts.size());
            List<String> locations = conflicts.get("com.acme.Duplicate");
            assertNotNull("the class must be named by its binary name: " + conflicts, locations);
            assertEquals("both entries must be listed: " + locations, 2, locations.size());

            String report = JQuickJarConflictDetector.report();
            assertTrue(report.contains("com.acme.Duplicate"));
            assertTrue(report.contains("duplicate classes found"));
            assertEquals("both archives are implicated",
                    2, JQuickJarConflictDetector.conflictCountByArchive().size());

            // A single entry is not a conflict.
            System.setProperty("java.class.path", first.getPath());
            assertTrue(JQuickJarConflictDetector.conflicts().isEmpty());
            assertTrue(JQuickJarConflictDetector.report().contains("no duplicate class found"));
        } finally {
            System.setProperty("java.class.path", original);
        }
    }

    /**
     * The ignore list is the reason a normal run does not drown in {@code META-INF.versions} entries
     * from multi-release jars.
     */
    @Test
    public void theDefaultIgnoreListSkipsMetadataEntries() throws Exception {
        String original = System.getProperty("java.class.path");
        File first = directoryWithVersionedClass("meta-first");
        File second = directoryWithVersionedClass("meta-second");
        try {
            System.setProperty("java.class.path", first.getPath() + File.pathSeparator
                    + second.getPath());
            assertTrue("META-INF.* must be ignored by default",
                    JQuickJarConflictDetector.conflicts().isEmpty());
            assertEquals("unless the ignore list is emptied",
                    1, JQuickJarConflictDetector.conflicts(null).size());
        } finally {
            System.setProperty("java.class.path", original);
        }
    }

    /**
     * The detector must survive a classpath full of things that do not exist, because in a container
     * it usually is.
     */
    @Test
    public void anUnreadableClasspathIsNotAConflict() {
        String original = System.getProperty("java.class.path");
        try {
            System.setProperty("java.class.path", "target/not-here.jar"
                    + File.pathSeparator + "target/missing-dir" + File.pathSeparator + "");
            assertTrue(JQuickJarConflictDetector.conflicts().isEmpty());
        } finally {
            System.setProperty("java.class.path", original);
        }
    }

    /**
     * Conflict detection is a startup scan, not an enhancer: switching it on must add byte-code work
     * to nothing, and must install cleanly whatever the real project classpath looks like.
     */
    @Test
    public void conflictDetectionAddsNoInstrumentationOfItsOwn() throws Exception {
        JQuickHarness harness = JQuickHarness.ofPlugins(
                RULES + ";classload.enabled=true;classload.conflict.detect=true",
                new JQuickTracePlugin(), new JQuickClassLoadPlugin());

        assertEquals("only the tracer may contribute an enhancer",
                1, harness.environment().getEnhancers().size());

        Class<?> service = harness.load(SERVICE);
        assertEquals("and the scan must not disturb normal execution", 3,
                call(service, service.getDeclaredConstructor().newInstance(),
                        "add", new Class<?>[]{int.class, int.class}, 1, 2));
    }

    /* ================================================================== helpers */

    private static JQuickEnvironment freshEnvironment() {
        JQuickEnvironment.reset();
        JQuickEnvironment environment = JQuickEnvironment.create(
                io.github.paohaijiao.jquick.agent.core.JQuickConfig.empty());
        JQuickEnvironment.install(environment);
        return environment;
    }

    /**
     * A classpath entry containing {@code com/acme/Duplicate.class}, so a duplicate can be produced
     * without shipping one.
     */
    private static File directoryWithDuplicateClass(String label) throws Exception {
        File root = new File("target/tmp-conflict-" + label + "-" + System.nanoTime());
        File directory = new File(root, "com" + File.separator + "acme");
        assertTrue("could not create " + directory, directory.mkdirs());
        writeClass(new File(directory, "Duplicate.class"));
        return root;
    }

    /**
     * A classpath entry whose only class sits under {@code META-INF/versions}, the multi-release
     * layout that the default ignore list exists for.
     */
    private static File directoryWithVersionedClass(String label) throws Exception {
        File root = new File("target/tmp-conflict-" + label + "-" + System.nanoTime());
        File directory = new File(root, "META-INF" + File.separator + "versions");
        assertTrue("could not create " + directory, directory.mkdirs());
        writeClass(new File(directory, "Duplicate.class"));
        return root;
    }

    private static void writeClass(File target) throws Exception {
        FileOutputStream out = new FileOutputStream(target);
        try {
            out.write(new byte[]{1, 2, 3});
        } finally {
            out.close();
        }
    }

    private static int total(Map<String, Integer> counts) {
        int total = 0;
        for (Integer value : counts.values()) {
            total += value;
        }
        return total;
    }

    private static boolean sorted(Map<String, Integer> counts) {
        String previous = null;
        for (String key : counts.keySet()) {
            if (previous != null && previous.compareTo(key) > 0) {
                return false;
            }
            previous = key;
        }
        return true;
    }

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
