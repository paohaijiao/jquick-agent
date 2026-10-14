package io.github.paohaijiao.jquick.agent.test;

import io.github.paohaijiao.jquick.agent.core.JQuickCategory;
import io.github.paohaijiao.jquick.agent.core.JQuickConfig;
import io.github.paohaijiao.jquick.agent.core.JQuickEnvironment;
import io.github.paohaijiao.jquick.agent.core.JQuickPlugin;

/**
 * Fixture: a third-party plugin with a name of its own.
 *
 * <p>The counterpart to {@link JQuickShadowedPlugin}. Together they cover the two branches of the
 * merge rule: a new name is added, a taken name is refused. Like its sibling it is not in the shipped
 * service file and exists only for the discovery test.
 *
 * @author Martin
 */
public final class JQuickExtraPlugin implements JQuickPlugin {

    /**
     * The name this fixture contributes.
     */
    public static final String EXTRA_NAME = "fixture-extra";

    @Override
    public String name() {
        return EXTRA_NAME;
    }

    @Override
    public JQuickCategory category() {
        return JQuickCategory.DIAGNOSTIC;
    }

    @Override
    public boolean isEnabled(JQuickConfig config) {
        return config.getBoolean(EXTRA_NAME + ".enabled", true);
    }

    @Override
    public void initialize(JQuickEnvironment environment) {
        // Nothing to install: the fixture exists to prove that discovery reaches this far.
    }

    @Override
    public String description() {
        return "fixture: an out-of-tree plugin";
    }
}
