package io.github.paohaijiao.jquick.agent.plugin.component;

import io.github.paohaijiao.jquick.agent.core.*;
import io.github.paohaijiao.jquick.agent.trace.JQuickSpanType;

import java.util.ArrayList;
import java.util.List;

/**
 * Reusable base for every "instrument an external component" capability.
 *
 * <p>HTTP, cache, message queue and the framework integrations all share one shape: <em>select
 * classes that belong to a known hierarchy, instrument a subset of their methods, label the
 * resulting spans with a component name, and optionally observe them at runtime</em>. Parameterising
 * that shape once is what keeps the individual plugins down to a handful of lines and what guarantees
 * that a fix in the emitter benefits all of them.
 *
 * <h3>Selection model</h3>
 * The target hierarchy is expressed with a {@link JQuickClassMatcher} built from
 * <b>superclass / interface names</b> rather than class-name prefixes. This is the technique every
 * production agent uses, and it is the only one that survives the real world: applications routinely
 * relocate drivers (shaded jars), wrap them (pooling DataSources, Lettuce vs Jedis), or use a
 * different implementation per environment, while the <em>interface</em> they implement stays stable.
 *
 * <p>Defaults are supplied so the plugin works with zero configuration, and every default can be
 * replaced through {@code <name>.includes}.
 *
 * @author Martin
 */
public class JQuickComponentPlugin implements JQuickPlugin {

    private final String name;

    private final String component;

    private final JQuickSpanType spanType;

    private final JQuickCategory category;

    private final JQuickClassMatcher classMatcher;

    private final JQuickMethodMatcher methodMatcher;

    private final JQuickMethodListener listener;

    private final String description;

    private final JQuickHookPlan planRequirement;

    private final List<JQuickClassEnhancer> enhancers = new ArrayList<JQuickClassEnhancer>();

    /**
     * @param name          plugin name and configuration prefix
     * @param component     label attached to the produced spans
     * @param spanType      {@code ENTRY} for inbound, {@code EXIT} for outbound integrations
     * @param category      functional family, used in the bootstrap report
     * @param classMatcher  hierarchy based selection
     * @param methodMatcher method-level selection
     * @param listener      optional runtime observer; may be {@code null}
     * @param description   one-line description for the bootstrap report
     */
    public JQuickComponentPlugin(String name, String component, JQuickSpanType spanType,
                                 JQuickCategory category, JQuickClassMatcher classMatcher,
                                 JQuickMethodMatcher methodMatcher, JQuickMethodListener listener,
                                 String description) {
        this(name, component, spanType, category, classMatcher, methodMatcher, listener,
                description, null);
    }

    /**
     * @param planRequirement additional hooks this component needs beyond the environment-wide plan,
     *                        e.g. argument transformation for a rewriting integration
     */
    public JQuickComponentPlugin(String name, String component, JQuickSpanType spanType,
                                 JQuickCategory category, JQuickClassMatcher classMatcher,
                                 JQuickMethodMatcher methodMatcher, JQuickMethodListener listener,
                                 String description, JQuickHookPlan planRequirement) {
        this.name = name;
        this.component = component;
        this.spanType = spanType;
        this.category = category;
        this.classMatcher = classMatcher;
        this.methodMatcher = methodMatcher;
        this.listener = listener;
        this.description = description;
        this.planRequirement = planRequirement;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public JQuickCategory category() {
        return category;
    }

    @Override
    public boolean isEnabled(JQuickConfig config) {
        return config.isPluginEnabled(name);
    }

    @Override
    public void initialize(JQuickEnvironment environment) {
        enhancers.add(new JQuickInstrumentEnhancer(name, classMatcher, methodMatcher,
                spanType, component, environment, planRequirement(environment.getConfig())));
        if (listener != null) {
            environment.addListener(listener);
        }
        configure(environment);
    }

    /**
     * The additional hooks this component needs, resolved against the configuration that is
     * actually in force.
     *
     * <p>The default returns the plan supplied to the constructor. Subclasses whose plan depends on
     * configuration override this instead of the constructor, so that they stay constructible with a
     * public no-argument constructor — a requirement of {@code ServiceLoader} discovery.
     */
    protected JQuickHookPlan planRequirement(JQuickConfig config) {
        return planRequirement;
    }

    /**
     * Hook for subclasses that need to register additional runtime behaviour — transformers, gates,
     * stub resolvers or extra listeners.
     */
    protected void configure(JQuickEnvironment environment) {
        // No additional behaviour by default.
    }

    @Override
    public List<JQuickClassEnhancer> enhancers() {
        return enhancers;
    }

    @Override
    public String description() {
        return description;
    }
}
