package io.github.paohaijiao.jquick.agent.bootstrap;

import io.github.paohaijiao.jquick.agent.core.JQuickConfig;
import io.github.paohaijiao.jquick.agent.core.JQuickPlugin;
import io.github.paohaijiao.jquick.agent.plugin.audit.JQuickAuditPlugin;
import io.github.paohaijiao.jquick.agent.plugin.classload.JQuickClassLoadPlugin;
import io.github.paohaijiao.jquick.agent.plugin.component.JQuickCachePlugin;
import io.github.paohaijiao.jquick.agent.plugin.component.JQuickHttpPlugin;
import io.github.paohaijiao.jquick.agent.plugin.component.JQuickMqPlugin;
import io.github.paohaijiao.jquick.agent.plugin.coverage.JQuickCoveragePlugin;
import io.github.paohaijiao.jquick.agent.plugin.crypto.JQuickCryptoPlugin;
import io.github.paohaijiao.jquick.agent.plugin.governance.JQuickGovernancePlugin;
import io.github.paohaijiao.jquick.agent.plugin.jdbc.JQuickJdbcPlugin;
import io.github.paohaijiao.jquick.agent.plugin.log.JQuickLogPlugin;
import io.github.paohaijiao.jquick.agent.plugin.mask.JQuickMaskPlugin;
import io.github.paohaijiao.jquick.agent.plugin.metrics.JQuickMetricsPlugin;
import io.github.paohaijiao.jquick.agent.plugin.mock.JQuickMockPlugin;
import io.github.paohaijiao.jquick.agent.plugin.security.JQuickSecurityPlugin;
import io.github.paohaijiao.jquick.agent.plugin.thread.JQuickThreadPlugin;
import io.github.paohaijiao.jquick.agent.plugin.trace.JQuickTracePlugin;
import io.github.paohaijiao.jquick.agent.support.JQuickLogger;

import java.util.*;

/**
 * The plugin catalogue: the only place that knows the names of the built-in capabilities.
 *
 * <p>Keeping this knowledge out of the kernel is the point of the plugin interface. The kernel
 * ({@code JQuickEnvironment}, {@code JQuickInstrumentEnhancer}, {@code JQuickSink}) never mentions a
 * capability by name; it only knows how to hand a class to a list of enhancers and how to dispatch an
 * event to a list of listeners. This class is where that list is assembled, and it is deliberately a
 * plain, readable, ordered list rather than a reflective scan: the install order determines the order
 * of the enhancer chain and therefore the order in which two capabilities see the same class, and an
 * ordering that can only be learned by running the program is an ordering nobody can debug.
 *
 * <h3>Two sources, one merge</h3>
 * <ol>
 *   <li>{@link #builtIn()} — the capabilities shipped in this jar, in a fixed order;</li>
 *   <li>{@link #discover(ClassLoader)} — anything on the classpath declaring itself through
 *       {@code META-INF/services/}{@code io.github.paohaijiao.jquick.agent.core.JQuickPlugin},
 *       which is how an out-of-tree capability is added without recompiling this module.</li>
 * </ol>
 * A discovered plugin whose {@link JQuickPlugin#name()} collides with a built-in loses: the built-in
 * wins, because a third-party jar must not be able to silently replace the tracer. The collision is
 * logged.
 *
 * <h3>Construction contract</h3>
 * Every plugin must be constructible with a public no-argument constructor. That is what makes the
 * SPI usable at all, and it is also the right design: a capability has no business holding the
 * operator's configuration before the operator's agent exists. Configuration is read in
 * {@link JQuickPlugin#initialize(io.github.paohaijiao.jquick.agent.core.JQuickEnvironment)}, from the
 * environment that is actually installed.
 *
 * @author Martin
 */
public final class JQuickPlugins {

    private JQuickPlugins() {
    }

    /**
     * The built-in capabilities, in install order.
     *
     * <p>Order rationale: observation first (trace, metrics), then the integration families that feed
     * it (HTTP, JDBC, cache, MQ), then the cross-cutting capabilities that either transform data or
     * enforce a policy (log, security, governance, audit, crypto, mask), then the developer-facing
     * tools (mock, thread, coverage, classload).
     */
    public static List<JQuickPlugin> builtIn() {
        return Arrays.asList(
                new JQuickTracePlugin(),
                new JQuickMetricsPlugin(),
                new JQuickHttpPlugin(),
                new JQuickJdbcPlugin(),
                new JQuickCachePlugin(),
                new JQuickMqPlugin(),
                new JQuickLogPlugin(),
                new JQuickSecurityPlugin(),
                new JQuickGovernancePlugin(),
                new JQuickAuditPlugin(),
                new JQuickCryptoPlugin(),
                new JQuickMaskPlugin(),
                new JQuickMockPlugin(),
                new JQuickThreadPlugin(),
                new JQuickCoveragePlugin(),
                new JQuickClassLoadPlugin());
    }

    /**
     * Discovers third-party plugins through the standard service loader.
     *
     * <p>A provider that cannot be instantiated is skipped with a log line — a broken third-party jar
     * must not prevent the agent from starting.
     */
    public static List<JQuickPlugin> discover(ClassLoader loader) {
        List<JQuickPlugin> found = new ArrayList<JQuickPlugin>();
        if (loader == null) {
            loader = JQuickPlugins.class.getClassLoader();
        }
        try {
            ServiceLoader<JQuickPlugin> services = ServiceLoader.load(JQuickPlugin.class, loader);
            Iterator<JQuickPlugin> iterator = services.iterator();
            while (true) {
                try {
                    if (!iterator.hasNext()) {
                        break;
                    }
                    JQuickPlugin plugin = iterator.next();
                    if (plugin != null) {
                        found.add(plugin);
                    }
                } catch (Throwable ex) {
                    // ServiceConfigurationError is thrown lazily by the iterator, so a single broken
                    // provider aborts iteration unless it is caught here.
                    JQuickLogger.warn("skipping an undiscoverable JQuickPlugin provider: " + ex);
                }
            }
        } catch (Throwable ex) {
            JQuickLogger.suppressed("plugin discovery failed", ex);
        }
        return found;
    }

    /**
     * The effective plugin list: built-ins in their declared order, then discovered plugins whose
     * name is not already taken.
     */
    public static List<JQuickPlugin> load(JQuickConfig config, ClassLoader loader) {
        Map<String, JQuickPlugin> byName = new LinkedHashMap<String, JQuickPlugin>();
        for (JQuickPlugin plugin : builtIn()) {
            byName.put(plugin.name(), plugin);
        }
        for (JQuickPlugin plugin : discover(loader)) {
            String name = plugin.name();
            JQuickPlugin existing = byName.get(name);
            if (existing != null) {
                if (existing.getClass() == plugin.getClass()) {
                    // The provider file also lists the built-in plugins, on purpose: it makes the
                    // discovery path run on every single boot, so it cannot silently rot. Seeing the
                    // same class twice is therefore expected, not a conflict.
                    continue;
                }
                JQuickLogger.warn("discovered plugin '" + name
                        + "' (" + plugin.getClass().getName()
                        + ") is shadowed by the built-in plugin of the same name");
                continue;
            }
            byName.put(name, plugin);
        }
        return new ArrayList<JQuickPlugin>(byName.values());
    }
}
