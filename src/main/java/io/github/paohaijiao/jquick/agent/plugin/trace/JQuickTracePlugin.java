package io.github.paohaijiao.jquick.agent.plugin.trace;

import io.github.paohaijiao.jquick.agent.annotation.JQuickIgnored;
import io.github.paohaijiao.jquick.agent.annotation.JQuickTrace;
import io.github.paohaijiao.jquick.agent.core.*;
import io.github.paohaijiao.jquick.agent.trace.JQuickSpanType;

import java.util.ArrayList;
import java.util.List;

/**
 * Core capability: method level distributed tracing.
 *
 * <p>This is the plugin every other capability builds upon. It instruments the selected methods with
 * the generic {@link JQuickInstrumentEnhancer} and attaches a {@link JQuickTraceListener} that turns
 * the resulting spans into a trace log.
 *
 * <p>Two selection strategies are supported:
 * <ul>
 *   <li><b>package driven</b> (default) — the transformer's global {@code includes}/{@code excludes}
 *       rules apply, and every eligible method of a matched class is instrumented;</li>
 *   <li><b>annotation driven</b> — {@code trace.annotation.only=true} restricts instrumentation to
 *       classes and methods carrying {@link JQuickTrace}, which is what a large codebase with a few
 *       interesting packages wants.</li>
 * </ul>
 *
 * <p>Either way {@link JQuickIgnored} is honoured as an opt-out, so a single hot method can be
 * excluded without changing the agent configuration and restarting.
 *
 * @author Martin
 */
public final class JQuickTracePlugin implements JQuickPlugin {

    /**
     * Plugin name, also the configuration prefix ({@code trace.enabled}).
     */
    public static final String NAME = "trace";

    /**
     * Component label attached to generic method spans.
     */
    public static final String COMPONENT = "method";

    /**
     * Dotted name of the declarative opt-in annotation.
     */
    static final String TRACE_ANNOTATION = JQuickTrace.class.getName();

    /**
     * Dotted name of the declarative opt-out annotation.
     */
    static final String IGNORED_ANNOTATION = JQuickIgnored.class.getName();

    private final List<JQuickClassEnhancer> enhancers = new ArrayList<JQuickClassEnhancer>();

    private static JQuickClassMatcher buildClassMatcher(JQuickConfig config) {
        if (config.getBoolean(JQuickConfig.TRACE_ANNOTATION_ONLY, false)) {
            // Only classes that asked to be traced, at class or method level.
            return JQuickClassMatcher.builder().matchAnnotation(TRACE_ANNOTATION).build();
        }
        // The transformer has already applied the global include/exclude rules.
        return JQuickClassMatcher.ALL;
    }

    private static JQuickMethodMatcher buildMethodMatcher(JQuickConfig config) {
        JQuickMethodMatcher.Builder builder = JQuickMethodMatcher.builder()
                .excludeAnnotation(IGNORED_ANNOTATION);
        if (config.getBoolean(JQuickConfig.TRACE_ANNOTATION_ONLY, false)) {
            return builder.matchAnnotation(TRACE_ANNOTATION).build();
        }
        builder.matchAll();
        builder.matchNames(config.getList("trace.include.methods"));
        for (String excluded : config.getList("trace.exclude.methods")) {
            builder.exclude(excluded);
        }
        for (String prefix : config.getList("trace.exclude.prefixes")) {
            builder.excludePrefix(prefix);
        }
        return builder.build();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public JQuickCategory category() {
        return JQuickCategory.TRACING;
    }

    @Override
    public boolean isEnabled(JQuickConfig config) {
        return config.getBoolean(JQuickConfig.TRACE_ENABLED, true);
    }

    @Override
    public void initialize(JQuickEnvironment environment) {
        JQuickConfig config = environment.getConfig();
        JQuickClassEnhancer enhancer = new JQuickInstrumentEnhancer(
                "method-trace",
                buildClassMatcher(config),
                buildMethodMatcher(config),
                JQuickSpanType.LOCAL,
                COMPONENT,
                environment);
        enhancers.add(enhancer);
        // The three payload flags are honoured here rather than in the enhancer, because asking for
        // them is what makes the emitter materialise the argument array and box the return value: the
        // listener's captureArguments()/captureResult() feed the hook plan.
        environment.addListener(new JQuickTraceListener(
                config.getBoolean(JQuickConfig.TRACE_PRINT, false),
                config.getLong(JQuickConfig.TRACE_SLOW_MS, 200L),
                config.getBoolean(JQuickConfig.TRACE_ARGS, false),
                config.getBoolean(JQuickConfig.TRACE_RESULT, false),
                config.getBoolean(JQuickConfig.TRACE_ERROR_STACK, false)));
    }

    @Override
    public List<JQuickClassEnhancer> enhancers() {
        return enhancers;
    }

    @Override
    public String description() {
        return "method level tracing: span, timing, trace id";
    }
}
