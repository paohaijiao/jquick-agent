package io.github.paohaijiao.jquick.agent.plugin.classload;

import io.github.paohaijiao.jquick.agent.core.*;
import io.github.paohaijiao.jquick.agent.support.JQuickLogger;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Capability: class-loading insight and sandbox rules.
 *
 * <p>Three deliverables, all of them diagnostics an operator asks for by name during an incident:
 * <ul>
 *   <li><b>jar conflicts</b> — which classes are provided more than once;</li>
 *   <li><b>class-loader census</b> — how many classes are loaded, and how many the agent can
 *       actually modify, which is the honest answer to "why is my class not instrumented";</li>
 *   <li><b>blacklist enforcement</b> — a gate that refuses execution of blacklisted operations, plus
 *       the public {@link JQuickClassLoadGuard#assertAllowed} assertion for class names arriving from
 *       an untrusted source.</li>
 * </ul>
 *
 * @author Martin
 */
public final class JQuickClassLoadPlugin implements JQuickPlugin {

    public static final String NAME = "classload";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public JQuickCategory category() {
        return JQuickCategory.DIAGNOSTIC;
    }

    @Override
    public boolean isEnabled(JQuickConfig config) {
        return config.getBoolean(JQuickConfig.CLASSLOAD_ENABLED, false);
    }

    @Override
    public void initialize(JQuickEnvironment environment) {
        JQuickConfig config = environment.getConfig();
        JQuickClassLoadGuard.configure(config.getList(JQuickConfig.CLASSLOAD_BLACKLIST));

        if (config.getBoolean(JQuickConfig.CLASSLOAD_CONFLICT, false)) {
            reportConflicts();
        }
        reportClassLoaders(environment);

        if (!JQuickClassLoadGuard.blacklist().isEmpty()) {
            environment.addGate(new JQuickBlacklistGate());
        }
    }

    private void reportConflicts() {
        try {
            Map<String, List<String>> conflicts = JQuickJarConflictDetector.conflicts();
            if (conflicts.isEmpty()) {
                JQuickLogger.info("classpath scan: no duplicate class found");
            } else {
                JQuickLogger.warn("classpath scan: " + conflicts.size()
                        + " duplicate classes found\n" + JQuickJarConflictDetector.report());
            }
        } catch (Throwable ex) {
            JQuickLogger.suppressed("classpath conflict detection failed", ex);
        }
    }

    private void reportClassLoaders(JQuickEnvironment environment) {
        try {
            io.github.paohaijiao.jquick.agent.core.JQuickInstrumentation instrumentation =
                    null;
            int loaded = io.github.paohaijiao.jquick.agent.core.JQuickInstrumentation
                    .loadedClassCount();
            if (loaded < 0) {
                JQuickLogger.debug("class-loader census unavailable: Instrumentation not bound");
                return;
            }
            environment.getMeters().gauge("jquick_loaded_classes", new io.github.paohaijiao.jquick
                    .agent.metrics.JQuickGauge.ValueSource() {
                @Override
                public double read() {
                    return io.github.paohaijiao.jquick.agent.core.JQuickInstrumentation
                            .loadedClassCount();
                }
            });
            JQuickLogger.info("class-loader census: " + loaded + " classes currently loaded"
                    + (""));
        } catch (Throwable ex) {
            JQuickLogger.suppressed("class-loader census failed", ex);
        }
    }

    @Override
    public List<JQuickClassEnhancer> enhancers() {
        return Collections.emptyList();
    }

    @Override
    public String description() {
        return "classpath conflict detection, class-loader census, sandbox blacklist";
    }

    /**
     * Refuses to execute an operation whose owner class is blacklisted.
     */
    private static final class JQuickBlacklistGate implements JQuickInvocationGate {

        @Override
        public boolean requiresArguments() {
            return false;
        }

        @Override
        public void inspect(JQuickInvocation invocation) throws JQuickRejectedException {
            JQuickClassLoadGuard.assertAllowed(invocation.getClassName());
        }

        @Override
        public String name() {
            return "sandbox-blacklist";
        }
    }
}
