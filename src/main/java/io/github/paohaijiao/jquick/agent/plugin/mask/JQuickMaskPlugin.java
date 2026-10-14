package io.github.paohaijiao.jquick.agent.plugin.mask;

import io.github.paohaijiao.jquick.agent.core.*;
import io.github.paohaijiao.jquick.agent.support.JQuickLogger;
import io.github.paohaijiao.jquick.agent.trace.JQuickSpanType;

import java.util.ArrayList;
import java.util.List;

/**
 * Capability: mask personal data on the way out of the selected methods.
 *
 * <p>Combine with {@code jdbc.includes} / the class-name selector to point it at the API facade that
 * returns user data. Because the transformation is a result rewrite, the method body does not change
 * — the caller simply receives a masked payload.
 *
 * @author Martin
 */
public final class JQuickMaskPlugin implements JQuickPlugin {

    public static final String NAME = "mask";

    private final List<JQuickClassEnhancer> enhancers = new ArrayList<JQuickClassEnhancer>();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public JQuickCategory category() {
        return JQuickCategory.DATA;
    }

    @Override
    public boolean isEnabled(JQuickConfig config) {
        return config.isPluginEnabled(NAME);
    }

    @Override
    public void initialize(JQuickEnvironment environment) {
        JQuickConfig config = environment.getConfig();
        List<String> includes = config.getList("mask.includes");
        if (includes.isEmpty()) {
            JQuickLogger.warn("mask plugin is enabled but mask.includes is empty: "
                    + "no class will be masked");
        }
        environment.addResultTransformer(new JQuickSensitiveTransformer(includes));
        enhancers.add(new JQuickInstrumentEnhancer(
                NAME,
                JQuickClassMatcher.byName(includes.toArray(new String[0])),
                JQuickMethodMatcher.all(),
                JQuickSpanType.LOCAL,
                NAME,
                environment,
                JQuickHookPlan.builder().transformResult().build()));
    }

    @Override
    public List<JQuickClassEnhancer> enhancers() {
        return enhancers;
    }

    @Override
    public String description() {
        return "sensitive data masking on return values";
    }
}
