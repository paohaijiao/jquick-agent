package io.github.paohaijiao.jquick.agent.plugin.log;

import io.github.paohaijiao.jquick.agent.core.*;
import io.github.paohaijiao.jquick.agent.trace.JQuickSpanType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Capability: inject the trace id into every log line, without touching the logging configuration.
 *
 * <p>Logging calls are a high-volume target, so this plugin is off by default and its selector is
 * explicit: it matches the concrete logger implementations of the mainstream frameworks, which
 * implement their framework's {@code Logger} interface <em>directly</em> — the property that makes
 * hierarchy based matching work here.
 *
 * <p>Because the rewrite happens on the message argument of a real call, it survives every logging
 * backend, including the ones that resolve their configuration at runtime.
 *
 * @author Martin
 */
public final class JQuickLogPlugin implements JQuickPlugin {

    public static final String NAME = "log";

    private static final List<String> DEFAULT_TARGETS = Arrays.asList(
            "org.slf4j.Logger",
            "org.apache.logging.log4j.Logger",
            "org.apache.log4j.Logger",
            "java.util.logging.Logger");

    private final List<JQuickClassEnhancer> enhancers = new ArrayList<JQuickClassEnhancer>();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public JQuickCategory category() {
        return JQuickCategory.LOGGING;
    }

    @Override
    public boolean isEnabled(JQuickConfig config) {
        return config.getBoolean(JQuickConfig.LOG_ENABLED, false);
    }

    @Override
    public void initialize(JQuickEnvironment environment) {
        JQuickConfig config = environment.getConfig();
        List<String> targets = config.getList(JQuickConfig.LOG_INCLUDES);
        if (targets.isEmpty()) {
            targets = DEFAULT_TARGETS;
        }
        boolean maskMessages = config.getBoolean(JQuickConfig.LOG_MASK_ENABLED, false);
        environment.addArgumentTransformer(new JQuickLogTransformer(maskMessages));
        enhancers.add(new JQuickInstrumentEnhancer(
                NAME,
                JQuickClassMatcher.builder().matchSuperTypes(targets).build(),
                JQuickMethodMatcher.builder()
                        .matchName("trace")
                        .matchName("debug")
                        .matchName("info")
                        .matchName("warn")
                        .matchName("error")
                        .matchName("fatal")
                        .build(),
                JQuickSpanType.LOCAL,
                NAME,
                environment,
                JQuickHookPlan.builder().transformArguments().build()));
    }

    @Override
    public List<JQuickClassEnhancer> enhancers() {
        return enhancers;
    }

    @Override
    public String description() {
        return "trace id injection and optional masking in the log stream";
    }
}
