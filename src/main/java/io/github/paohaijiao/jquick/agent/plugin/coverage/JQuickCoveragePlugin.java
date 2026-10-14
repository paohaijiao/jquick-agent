package io.github.paohaijiao.jquick.agent.plugin.coverage;

import io.github.paohaijiao.jquick.agent.core.*;
import io.github.paohaijiao.jquick.agent.support.JQuickLogger;
import io.github.paohaijiao.jquick.agent.trace.JQuickSpanType;

import java.util.ArrayList;
import java.util.List;

/**
 * Capability: method-level execution coverage.
 *
 * <p>Useful in two very different situations: an integration suite that wants to know which endpoints
 * were never exercised, and a production canary that wants to know whether a freshly deployed code
 * path is being reached at all.
 *
 * <p>The plugin registers itself in a static field so a test or an admin endpoint can obtain the
 * report without holding a reference to the plugin instance — which matters because the agent is
 * installed outside the application's object graph.
 *
 * @author Martin
 */
public final class JQuickCoveragePlugin implements JQuickPlugin {

    public static final String NAME = "coverage";

    private static volatile JQuickCoverage instance;

    private final List<JQuickClassEnhancer> enhancers = new ArrayList<JQuickClassEnhancer>();

    /**
     * The active coverage registry, or {@code null} when the plugin is disabled.
     */
    public static JQuickCoverage getInstance() {
        return instance;
    }

    /**
     * Reconciles the instrumented set with what the agent actually rewrote, and returns the report.
     *
     * <p>The reconciliation matters. Coverage has two halves — <em>what was instrumented</em> and
     * <em>what ran</em> — and the executed half is easy (it is observed as it happens) while the
     * instrumented half is not: at the moment this plugin initialises, no class has been rewritten
     * yet, so it cannot enumerate its own targets. The method registry is the authoritative record of
     * what the enhancer chain accepted, so the report is built from it rather than from a second list
     * that would inevitably drift.
     *
     * @return the text report, or an explanation when coverage is not active
     */
    public static String report() {
        JQuickCoverage coverage = instance;
        if (coverage == null) {
            return "coverage is not active (set coverage.enabled=true)\n";
        }
        JQuickEnvironment environment = JQuickEnvironment.get();
        if (environment != null) {
            for (JQuickMethodMeta meta : environment.getRegistry().snapshot()) {
                coverage.register(meta.getSignature());
            }
        }
        return coverage.report();
    }

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
        return config.getBoolean(JQuickConfig.COVERAGE_ENABLED, false);
    }

    @Override
    public void initialize(JQuickEnvironment environment) {
        JQuickConfig config = environment.getConfig();
        List<String> includes = config.getList(JQuickConfig.COVERAGE_INCLUDES);
        if (includes.isEmpty()) {
            JQuickLogger.warn("coverage plugin is enabled but " + JQuickConfig.COVERAGE_INCLUDES
                    + " is empty: nothing will be measured");
        }
        final JQuickCoverage coverage = new JQuickCoverage();
        instance = coverage;
        environment.addListener(new JQuickMethodListener() {
            @Override
            public void onMethodEvent(JQuickMethodEvent event) {
                if (event.getPhase() == JQuickMethodEvent.JQuickPhase.ENTER) {
                    coverage.record(event.getSignature());
                }
            }

            @Override
            public String name() {
                return "coverage";
            }
        });
        enhancers.add(new JQuickInstrumentEnhancer(
                NAME,
                JQuickClassMatcher.byName(includes.toArray(new String[0])),
                JQuickMethodMatcher.all(),
                JQuickSpanType.LOCAL,
                NAME,
                environment));
    }

    @Override
    public List<JQuickClassEnhancer> enhancers() {
        return enhancers;
    }

    @Override
    public String description() {
        return "method-level execution coverage";
    }
}
