package io.github.paohaijiao.jquick.agent;

import io.github.paohaijiao.jquick.agent.bootstrap.JQuickBootstrap;
import io.github.paohaijiao.jquick.agent.bootstrap.JQuickPlugins;
import io.github.paohaijiao.jquick.agent.core.JQuickConfig;
import io.github.paohaijiao.jquick.agent.core.JQuickEnvironment;
import io.github.paohaijiao.jquick.agent.core.JQuickInstrumentation;
import io.github.paohaijiao.jquick.agent.core.JQuickPlugin;
import io.github.paohaijiao.jquick.agent.plugin.trace.JQuickTracePlugin;
import io.github.paohaijiao.jquick.agent.test.JQuickExtraPlugin;
import io.github.paohaijiao.jquick.agent.test.JQuickRecordingInstrumentation;
import io.github.paohaijiao.jquick.agent.test.JQuickShadowedPlugin;
import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the install sequence and the plugin catalogue.
 *
 * <p>Two invariants are asserted here that no other suite can reach:
 *
 * <ul>
 *   <li><b>exactly one transformer</b> is registered. That is the architectural decision the whole
 *       framework rests on — a single transformer that threads each enhancer's output into the next is
 *       what makes two capabilities on one class behave predictably. A second registration would mean
 *       two independent passes over the same bytes, and the second one would see the first one's
 *       output with no way to know it;</li>
 *   <li><b>a third-party plugin cannot shadow a built-in</b>. A capability jar on the classpath must
 *       be able to add a capability, never to replace the tracer with something else.</li>
 * </ul>
 *
 * @author Martin
 */
public class BootstrapTest {

    private static final String RULES = "quiet=true";

    @After
    public void tearDown() {
        // Both are process-wide by design, because injected byte-code can only reach a static anchor.
        // Leaving a fake JVM service bound would make later suites observe capabilities the real JVM
        // never granted.
        JQuickInstrumentation.bind(null);
        JQuickEnvironment.reset();
    }

    /* ================================================================== catalogue */

    /**
     * The catalogue is a hand-written ordered list rather than a classpath scan, so the order — which
     * determines which capability sees a class first — is reviewable. This test freezes it.
     */
    @Test
    public void builtInCatalogueIsCompleteAndOrdered() {
        List<JQuickPlugin> plugins = JQuickPlugins.builtIn();
        assertEquals("the shipped capability count is part of the public contract",
                16, plugins.size());

        List<String> names = new ArrayList<String>();
        for (JQuickPlugin plugin : plugins) {
            names.add(plugin.name());
        }
        assertEquals("names must be unique: " + names,
                names.size(), new LinkedHashSet<String>(names).size());

        // Observation before the integrations that feed it, developer tools last.
        assertEquals("trace", names.get(0));
        assertEquals("metrics", names.get(1));
        assertTrue("every advertised family must be present: " + names, names.containsAll(Arrays.asList(
                "trace", "metrics", "http", "jdbc", "cache", "mq", "log", "security",
                "governance", "audit", "crypto", "mask", "mock", "thread", "coverage", "classload")));
        assertEquals("classload", names.get(names.size() - 1));
    }

    /**
     * Every plugin must be constructible with no arguments, because that is what the service loader
     * requires. A capability that needs the operator's configuration to build itself could never be
     * discovered — it has to read its configuration in {@code initialize}.
     */
    @Test
    public void everyPluginHasAPublicNoArgumentConstructor() throws Exception {
        for (JQuickPlugin plugin : JQuickPlugins.builtIn()) {
            Class<?> type = plugin.getClass();
            assertNotNull("no no-arg constructor on " + type.getName(),
                    type.getConstructor());
            assertTrue("the constructor must be public on " + type.getName(),
                    java.lang.reflect.Modifier.isPublic(type.getConstructor().getModifiers()));
            JQuickPlugin fresh = (JQuickPlugin) type.getConstructor().newInstance();
            assertEquals("a fresh instance must report the same name",
                    plugin.name(), fresh.name());
            assertNotNull("every plugin must describe itself", plugin.description());
        }
    }

    /**
     * The shipped service file lists the built-ins on purpose: it makes the discovery path execute on
     * every boot, so it cannot rot unnoticed. Discovery must therefore return them without duplicating
     * them, and {@code load} must collapse the two sources into one entry per name.
     */
    @Test
    public void discoveryFindsTheProvidersAndLoadDeduplicatesThem() {
        List<JQuickPlugin> discovered =
                JQuickPlugins.discover(JQuickPlugins.class.getClassLoader());
        assertFalse("the service file must list the built-ins", discovered.isEmpty());

        Set<Class<?>> types = new LinkedHashSet<Class<?>>();
        for (JQuickPlugin plugin : discovered) {
            types.add(plugin.getClass());
        }
        assertEquals("the service file must name every built-in exactly once",
                JQuickPlugins.builtIn().size(), types.size());

        List<JQuickPlugin> loaded = JQuickPlugins.load(JQuickConfig.empty(), null);
        assertEquals("a provider identical to a built-in must not be added twice",
                JQuickPlugins.builtIn().size(), loaded.size());
        assertEquals("trace must still be the bundled implementation",
                JQuickTracePlugin.class, loaded.get(0).getClass());
    }

    /**
     * The merge rule, both branches. A service file generated at test time is pointed at by a class
     * loader, which is the only way to introduce an out-of-tree provider without shipping one.
     */
    @Test
    public void aNewPluginIsAddedAndACollidingOneIsRefused() throws Exception {
        ClassLoader loader = loaderAdvertising(JQuickShadowedPlugin.class, JQuickExtraPlugin.class);
        List<JQuickPlugin> loaded = JQuickPlugins.load(JQuickConfig.empty(), loader);

        List<String> names = new ArrayList<String>();
        for (JQuickPlugin plugin : loaded) {
            names.add(plugin.name());
        }
        assertTrue("an out-of-tree plugin must be added: " + names,
                names.contains(JQuickExtraPlugin.EXTRA_NAME));
        assertEquals("only one plugin may own a name: " + names,
                1, occurrences(names, JQuickShadowedPlugin.SHADOWED_NAME));
        for (JQuickPlugin plugin : loaded) {
            if (JQuickShadowedPlugin.SHADOWED_NAME.equals(plugin.name())) {
                assertEquals("the built-in must win the collision",
                        JQuickTracePlugin.class, plugin.getClass());
            }
        }
        assertEquals(JQuickPlugins.builtIn().size() + 1, loaded.size());
    }

    /* ================================================================== install sequence */

    /**
     * The bootstrap has to survive a missing JVM service: {@code premain} runs before the
     * application's first line, and a half-installed agent that also kills the JVM is the worst
     * possible outcome. In that mode the environment, the catalogue and the transformer must still
     * exist, because in-process users of the API rely on them.
     */
    @Test
    public void installWithoutAJvmServiceStillBuildsTheRuntime() {
        JQuickBootstrap bootstrap = JQuickBootstrap.install(
                RULES + ";trace.enabled=true", null, true);
        assertNotNull("installation must not be abandoned without an Instrumentation", bootstrap);
        assertTrue(bootstrap.isPremain());
        assertNull("no port was configured, so nothing is listening", bootstrap.getExporter());
        assertNotNull(bootstrap.getTransformer());
        assertNotNull(bootstrap.getEnvironment());
        assertNotNull("the tracer was requested", findByName(bootstrap.getActivePlugins(), "trace"));

        String report = bootstrap.report();
        assertTrue("the report must state the mode: " + report, report.contains("via premain"));
        assertTrue("the report must name the capabilities: " + report, report.contains("trace"));
        assertTrue("the report must admit the JDK service is missing: " + report,
                report.contains("redefine=false, retransform=false"));
        assertNotNull(JQuickBootstrap.version());
    }

    /**
     * A capability that was not asked for must not be initialised: not merely inert, but absent, with
     * no listener and no enhancer registered. This is the safety property that lets the agent be
     * installed on a production system before anything is switched on.
     */
    /**
     * The default posture, which is the single most important safety property of the framework: the
     * agent may be installed on a production system before anyone has decided what it should do.
     *
     * <p>Observation is the only thing that is on. Everything that can change behaviour, transform
     * data or refuse a call is strictly opt-in, and an idle capability is not merely inert — it is
     * neither initialised nor collected, so it registers no listener, no gate and no enhancer.
     */
    @Test
    public void theDefaultPostureEnablesOnlyObservation() {
        JQuickBootstrap bootstrap = JQuickBootstrap.install(RULES, null, true);
        assertNotNull(bootstrap);

        // A tracer that has to be switched on cannot diagnose a startup problem, so it is on; and the
        // registry always exists so anything that fires early is counted rather than lost.
        assertNotNull("the tracer is on by default",
                findByName(bootstrap.getActivePlugins(), "trace"));
        assertNotNull("metrics are on by default",
                findByName(bootstrap.getActivePlugins(), "metrics"));

        List<String> active = new ArrayList<String>();
        for (JQuickPlugin plugin : bootstrap.getActivePlugins()) {
            active.add(plugin.name());
        }
        for (String optIn : Arrays.asList("jdbc", "http", "cache", "mq", "log", "security",
                "governance", "audit", "crypto", "mask", "mock", "thread", "coverage", "classload")) {
            assertFalse("'" + optIn + "' must be opt-in but was active: " + active,
                    active.contains(optIn));
        }
        assertEquals("only the two observers are active: " + active, 2, active.size());
        assertEquals("the whole catalogue stays available", 16, bootstrap.getPlugins().size());

        // No hook beyond the baseline span: nothing allocated, nothing transformed, nothing refused.
        assertFalse(bootstrap.getEnvironment().computePlan().isCaptureArguments());
        assertFalse(bootstrap.getEnvironment().computePlan().isCaptureResult());
        assertFalse(bootstrap.getEnvironment().computePlan().isMockCheck());
        assertFalse(bootstrap.getEnvironment().computePlan().isWrapThreadPoolTask());
        assertNull("no port was configured, so nothing is listening", bootstrap.getExporter());
    }

    /**
     * The central architectural invariant. One transformer, registered once, and registered as
     * retransform-capable exactly when the JVM says it is — otherwise an attach would silently
     * instrument nothing.
     */
    @Test
    public void exactlyOneTransformerIsRegistered() {
        JQuickRecordingInstrumentation instrumentation =
                new JQuickRecordingInstrumentation(true, true);
        JQuickBootstrap bootstrap = JQuickBootstrap.install(
                RULES + ";trace.enabled=true", instrumentation, true);

        assertNotNull(bootstrap);
        assertEquals("one transformer, never a chain of them",
                1, instrumentation.getTransformers().size());
        assertEquals("the registered transformer must be the bootstrap's",
                bootstrap.getTransformer(), instrumentation.getTransformers().get(0));
        assertEquals("it must be registered as retransform-capable when the JVM supports it",
                Boolean.TRUE, instrumentation.getRetransformFlags().get(0));
        assertTrue(bootstrap.getEnvironment().getEnhancers().size() >= 1);
    }

    /**
     * Retransformation policy. On an attach, instrumenting nothing is never what the operator
     * wanted; on {@code premain} almost nothing is loaded yet. Both must be overridable, because the
     * framework cannot know whether the application preloaded its own classes.
     */
    @Test
    public void retransformationFollowsTheLaunchModeAndCanBeOverridden() {
        // Every instance sees one loaded application class, so "no retransformation" is a decision the
        // agent made rather than an empty target list the JVM never heard about.
        assertEquals("premain leaves already-loaded classes alone", 0,
                batchesAfter(RULES + ";trace.enabled=true", true));
        assertEquals("an attach must replay over the loaded class graph", 1,
                batchesAfter(RULES + ";trace.enabled=true", false));
        assertEquals("premain can be forced to retransform", 1,
                batchesAfter(RULES + ";trace.enabled=true;retransform=true", true));
        assertEquals("an attach can be told not to retransform", 0,
                batchesAfter(RULES + ";trace.enabled=true;retransform=false", false));
    }

    /**
     * Installs with a recording JVM service and returns how many retransformation batches the agent
     * issued.
     */
    private static int batchesAfter(String agentArgs, boolean premain) {
        JQuickRecordingInstrumentation instrumentation =
                new JQuickRecordingInstrumentation(true, true);
        instrumentation.withLoadedClasses(example.app.FixtureService.class);
        JQuickBootstrap.install(agentArgs + ";includes=example.app.*", instrumentation, premain);
        return instrumentation.getRetransformBatches().size();
    }

    /**
     * Without {@code Can-Retransform-Classes} the JVM refuses, and the agent must degrade quietly
     * rather than fail the install — an attached agent that cannot see preloaded classes is still
     * useful for everything loaded afterwards.
     */
    @Test
    public void theInstallSurvivesAJvmThatRefusesRetransformation() {
        JQuickRecordingInstrumentation instrumentation =
                new JQuickRecordingInstrumentation(false, false);
        instrumentation.withLoadedClasses(String.class, int.class);

        JQuickBootstrap bootstrap = JQuickBootstrap.install(
                RULES + ";trace.enabled=true", instrumentation, false);
        assertNotNull("a refused retransformation must not abort the install", bootstrap);
        assertEquals(1, instrumentation.getTransformers().size());
        assertEquals("nothing may be retransformed",
                0, instrumentation.getRetransformBatches().size());
        assertEquals("the registration must not claim an unsupported capability",
                Boolean.FALSE, instrumentation.getRetransformFlags().get(0));
    }

    /**
     * The retransformation pass must walk the loaded class graph and skip what the JVM cannot modify,
     * rather than asking for everything and hoping.
     */
    @Test
    public void theRetransformationPassFiltersByTheTransformerPredicate() {
        JQuickRecordingInstrumentation instrumentation =
                new JQuickRecordingInstrumentation(true, true);
        // A realistic loaded-class inventory: the JDK, the agent itself, primitives, arrays and one
        // application class that the include rule selects.
        instrumentation.withLoadedClasses(String.class, JQuickBootstrap.class, int.class,
                int[].class, example.app.FixtureService.class);

        JQuickBootstrap.install(RULES + ";includes=example.app.*;trace.enabled=true",
                instrumentation, false);

        assertEquals("only the included application class may have been offered",
                1, instrumentation.retransformCallCount());
        assertEquals(example.app.FixtureService.class,
                instrumentation.getRetransformBatches().get(0)[0]);
    }

    /* ================================================================== helpers */

    private static JQuickPlugin findByName(List<JQuickPlugin> plugins, String name) {
        for (JQuickPlugin plugin : plugins) {
            if (name.equals(plugin.name())) {
                return plugin;
            }
        }
        return null;
    }

    private static int occurrences(List<String> values, String needle) {
        int count = 0;
        for (String value : values) {
            if (needle.equals(value)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Writes a service file listing the supplied providers into a temporary directory and returns a
     * loader that exposes it. The parent loader still supplies the shipped service file and the
     * provider classes themselves, which is exactly how a third-party jar composes with the agent.
     */
    private static ClassLoader loaderAdvertising(Class<?>... providers) throws Exception {
        File root = new File("target/tmp-discovery-" + System.nanoTime());
        File directory = new File(root, "META-INF/services");
        assertTrue("could not create " + directory, directory.mkdirs());
        File serviceFile = new File(directory, JQuickPlugin.class.getName());

        Writer writer = new OutputStreamWriter(new FileOutputStream(serviceFile), "UTF-8");
        try {
            for (Class<?> provider : providers) {
                writer.write(provider.getName());
                writer.write('\n');
            }
            writer.flush();
        } finally {
            writer.close();
        }
        URL url = root.toURI().toURL();
        return new URLClassLoader(new URL[]{url}, BootstrapTest.class.getClassLoader());
    }
}
