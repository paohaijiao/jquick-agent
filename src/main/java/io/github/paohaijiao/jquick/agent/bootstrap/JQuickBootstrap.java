package io.github.paohaijiao.jquick.agent.bootstrap;

import io.github.paohaijiao.jquick.agent.core.*;
import io.github.paohaijiao.jquick.agent.support.JQuickLogger;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The installer: turns "the JVM handed us an {@link Instrumentation}" into "the capabilities are
 * live".
 *
 * <p>Everything that used to be implicit in the agent entry point lives here explicitly, in the order
 * it must happen:
 * <ol>
 *   <li><b>parse</b> the argument string;</li>
 *   <li><b>create</b> the environment and publish it as the runtime singleton, so that both the
 *       plugins and the already-injected byte-code resolve to the same object;</li>
 *   <li><b>bind</b> the JVM service;</li>
 *   <li><b>load</b> the plugin catalogue;</li>
 *   <li><b>filter</b> by {@code isEnabled}, then <b>initialize</b> each surviving plugin — this is
 *       where enhancers, listeners, gates, transformers and stub resolvers are registered;</li>
 *   <li><b>collect</b> the enhancers from the plugins into the environment;</li>
 *   <li><b>register</b> the single transformer;</li>
 *   <li><b>retransform</b> the classes that are already loaded, if this is an attach or if the
 *       operator asked for it;</li>
 *   <li><b>expose</b> the metrics endpoint, if a port was configured;</li>
 *   <li><b>report</b> exactly what happened.</li>
 * </ol>
 *
 * <h3>Why the order is not negotiable</h3>
 * The environment has to exist before any plugin is initialised, because a plugin acquires its meter
 * registry there. The enhancers have to be collected before the transformer is registered, because a
 * class loaded in between would be screened against an incomplete enhancer list — and there is no
 * second chance for that class. The transformer has to be registered before the retransformation
 * pass, because {@code retransformClasses} replays the registered transformers and a class would
 * otherwise be reverted to exactly what it already was.
 *
 * <h3>Never throws</h3>
 * {@link #install} is called from {@code premain}, i.e. before the application's first line runs. An
 * exception here kills the JVM with no application-level stack trace and no obvious cause. Every step
 * is therefore individually guarded: a plugin that fails to initialise is dropped with a log line, the
 * others still install, and the application still starts.
 *
 * @author Martin
 */
public final class JQuickBootstrap {

    private static volatile JQuickBootstrap current;

    private final JQuickConfig config;

    private final JQuickEnvironment environment;

    private final JQuickTransformer transformer;

    private final List<JQuickPlugin> plugins;

    private final List<JQuickPlugin> activePlugins;

    private final boolean premain;

    private volatile JQuickMetricsExporter exporter;

    private JQuickBootstrap(JQuickConfig config, JQuickEnvironment environment, JQuickTransformer transformer, List<JQuickPlugin> plugins, List<JQuickPlugin> activePlugins, boolean premain) {
        this.config = config;
        this.environment = environment;
        this.transformer = transformer;
        this.plugins = plugins;
        this.activePlugins = activePlugins;
        this.premain = premain;
    }

    /**
     * Installs the agent. Never throws.
     *
     * @param agentArgs       the raw argument string from {@code -javaagent:...=args} or from
     *                        {@code VirtualMachine.loadAgent}; may be {@code null}
     * @param instrumentation the JVM service; {@code null} disables all byte-code work but still
     *                        leaves the runtime usable in-process
     * @param premain         {@code true} when called from {@code premain}, {@code false} for a
     *                        dynamic attach
     * @return the install record, or {@code null} when installation could not proceed at all
     */
    public static JQuickBootstrap install(String agentArgs, Instrumentation instrumentation,
                                          boolean premain) {
        try {
            return doInstall(agentArgs, instrumentation, premain);
        } catch (Throwable ex) {
            // Absolute last line of defence: the host application must survive our mistakes.
            JQuickLogger.error("agent installation failed; the application continues without "
                    + "instrumentation", ex);
            return null;
        }
    }

    private static JQuickBootstrap doInstall(String agentArgs, Instrumentation instrumentation, boolean premain) {
        Map<String, String> arguments = JQuickArgumentParser.parse(agentArgs);
        JQuickConfig config = JQuickConfig.of(arguments);
        JQuickLogger.setQuiet(config.getBoolean(JQuickConfig.QUIET, false));
        JQuickLogger.setDebug(config.getBoolean(JQuickConfig.DEBUG, false));

        // Step 2-3: one environment, published before anything can observe it.
        JQuickEnvironment environment = JQuickEnvironment.create(config);
        JQuickEnvironment.install(environment);
        JQuickInstrumentation.bind(instrumentation);

        boolean quiet = config.getBoolean(JQuickConfig.QUIET, false);
        if (!quiet) {
            JQuickLogger.info("installing (mode=" + (premain ? "premain" : "agentmain") + ")"
                    + (arguments.isEmpty() ? " with default configuration" : ""));
        }

        // Step 4-6: catalogue, filter, initialise, collect.
        List<JQuickPlugin> discovered = JQuickPlugins.load(config, JQuickBootstrap.class.getClassLoader());
        List<JQuickPlugin> active = new ArrayList<JQuickPlugin>();
        int enhancerCount = 0;
        for (JQuickPlugin plugin : discovered) {
            boolean enabled;
            try {
                enabled = plugin.isEnabled(config);
            } catch (Throwable ex) {
                JQuickLogger.warn("plugin '" + plugin.name()
                        + "' failed its enabled check and is disabled: " + ex);
                continue;
            }
            if (!enabled) {
                JQuickLogger.debug("plugin disabled: " + plugin.name());
                continue;
            }
            try {
                plugin.initialize(environment);
                environment.addPlugin(plugin);
                for (JQuickClassEnhancer enhancer : plugin.enhancers()) {
                    environment.addEnhancer(enhancer);
                    enhancerCount++;
                }
                active.add(plugin);
            } catch (Throwable ex) {
                JQuickLogger.warn("plugin '" + plugin.name()
                        + "' failed to initialize and is skipped: " + ex);
            }
        }

        // Step 7: the single transformer.
        JQuickTransformer transformer = new JQuickTransformer(environment);
        if (instrumentation == null) {
            JQuickLogger.warn("no Instrumentation available: runtime capabilities work only for "
                    + "code that calls them directly");
            return new JQuickBootstrap(config, environment, transformer, discovered, active, premain);
        }
        boolean retransformCapable = JQuickInstrumentation.canRetransform();
        try {
            instrumentation.addTransformer(transformer, retransformCapable);
        } catch (Throwable ex) {
            JQuickLogger.error("could not register the class file transformer", ex);
            return new JQuickBootstrap(config, environment, transformer, discovered, active, premain);
        }

        JQuickBootstrap bootstrap =
                new JQuickBootstrap(config, environment, transformer, discovered, active, premain);

        // Step 8: retransformation. On an attach this is the whole point — the application is
        // already running. On premain it is normally unnecessary, but it is what makes
        // "-javaagent" behave the same as "attach" for a framework that preloaded classes.
        if (retransformCapable && shouldRetransform(config, premain)) {
            int retransformed = retransformLoaded(transformer);
            JQuickLogger.info("retransformed " + retransformed + " already-loaded class(es)");
        } else if (!premain && !retransformCapable) {
            JQuickLogger.warn("the JVM refused retransformation (manifest attribute "
                    + "Can-Retransform-Classes); classes loaded before the attach stay untouched");
        }

        // Step 9: exposure.
        bootstrap.exporter = JQuickMetricsExporter.start(environment);

        // Step 10: honesty. An operator should never have to guess which capabilities are live.
        if (!quiet) {
            JQuickLogger.info("active capabilities: " + describeNames(active));
            JQuickLogger.info("runtime: " + environment.describe());
            JQuickLogger.info("hook plan: " + environment.computePlan());
            JQuickLogger.info(bootstrap.report());
        }
        current = bootstrap;
        return bootstrap;
    }

    /**
     * Whether already-loaded classes should be retransformed.
     *
     * <p>Default: yes on attach, no on {@code premain}. Attaching to a running JVM and instrumenting
     * nothing is never what the operator wanted; on {@code premain} almost nothing is loaded yet, so
     * the pass costs a full class-graph walk for no gain. {@code retransform=false} and
     * {@code retransform=true} override either default.
     */
    private static boolean shouldRetransform(JQuickConfig config, boolean premain) {
        return config.getBoolean(JQuickConfig.RETRANSFORM, !premain);
    }

    private static int retransformLoaded(final JQuickTransformer transformer) {
        return JQuickInstrumentation.retransformMatching(
                new JQuickInstrumentation.JQuickClassPredicate() {
                    @Override
                    public boolean test(Class<?> candidate) {
                        return transformer.isCandidate(candidate.getName());
                    }
                });
    }

    private static String describeNames(List<JQuickPlugin> plugins) {
        if (plugins.isEmpty()) {
            return "(none)";
        }
        StringBuilder sb = new StringBuilder();
        for (JQuickPlugin plugin : plugins) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(plugin.name());
        }
        return sb.toString();
    }


    /**
     * The install record of the running agent, or {@code null} when it was not installed through the
     * JVM's agent entry points (for example inside a unit test).
     */
    public static JQuickBootstrap get() {
        return current;
    }

    /**
     * The implementation version from the jar manifest, or {@code "dev"} outside a packaged jar.
     */
    public static String version() {
        try {
            Package pkg = JQuickBootstrap.class.getPackage();
            String version = pkg == null ? null : pkg.getImplementationVersion();
            return version == null || version.trim().isEmpty() ? "dev" : version;
        } catch (Throwable ex) {
            return "dev";
        }
    }

    public JQuickConfig getConfig() {
        return config;
    }

    public JQuickEnvironment getEnvironment() {
        return environment;
    }

    public JQuickTransformer getTransformer() {
        return transformer;
    }

    /**
     * Every plugin the catalogue produced, enabled or not.
     */
    public List<JQuickPlugin> getPlugins() {
        return plugins;
    }

    /**
     * The plugins that were enabled and initialised successfully.
     */
    public List<JQuickPlugin> getActivePlugins() {
        return activePlugins;
    }

    public JQuickMetricsExporter getExporter() {
        return exporter;
    }

    public boolean isPremain() {
        return premain;
    }

    /**
     * Stops the HTTP endpoint. Instrumentation itself cannot be undone — {@code addTransformer} has
     * no counterpart — but the agent can always be made inert through
     * {@link JQuickEnvironment#setEnabled(boolean)}.
     */
    public void stop() {
        if (exporter != null) {
            exporter.stop();
            exporter = null;
        }
    }

    /**
     * Multi-line report shown by the entry points and reusable in a "diagnose the agent" endpoint.
     */
    public String report() {
        String sb = "jquick-agent " + version() + " installed" +
                (premain ? " via premain" : " via attach") + '\n' +
                "  capabilities:\n" + environment.report() +
                "  disabled: " + describeNames(disabledPlugins()) + '\n' +
                "  transformer: " + transformer.stats() + '\n' +
                "  instrumentation: redefine=" + JQuickInstrumentation.canRedefine() +
                ", retransform=" + JQuickInstrumentation.canRetransform() +
                ", loadedClasses=" + JQuickInstrumentation.loadedClassCount() +
                '\n' +
                "  exporter: " + (exporter == null
                ? "off" : exporter.getBindAddress() + ":" + exporter.getPort()
                + exporter.getMetricsPath()) +
                '\n';
        return sb;
    }

    private List<JQuickPlugin> disabledPlugins() {
        List<JQuickPlugin> disabled = new ArrayList<JQuickPlugin>();
        for (JQuickPlugin plugin : plugins) {
            if (!activePlugins.contains(plugin)) {
                disabled.add(plugin);
            }
        }
        return disabled;
    }
}
