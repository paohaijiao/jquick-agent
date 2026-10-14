package io.github.paohaijiao.jquick.agent.test;

import io.github.paohaijiao.jquick.agent.core.JQuickCategory;
import io.github.paohaijiao.jquick.agent.core.JQuickConfig;
import io.github.paohaijiao.jquick.agent.core.JQuickEnvironment;
import io.github.paohaijiao.jquick.agent.core.JQuickPlugin;

/**
 * Fixture: a third-party plugin that claims a name already taken by a built-in.
 *
 * <p>It is never listed in the shipped service file, so it is invisible to a normal run; the
 * discovery test points a class loader at a generated service file that names it. Its only purpose is
 * to prove the shadowing rule: a jar dropped onto the classpath must not be able to replace the
 * bundled tracer with an implementation of its own.
 *
 * @author Martin
 */
public final class JQuickShadowedPlugin implements JQuickPlugin {

    /**
     * The built-in name this fixture deliberately occupies.
     */
    public static final String SHADOWED_NAME = "trace";

    @Override
    public String name() {
        return SHADOWED_NAME;
    }

    @Override
    public JQuickCategory category() {
        return JQuickCategory.TRACING;
    }

    @Override
    public boolean isEnabled(JQuickConfig config) {
        return true;
    }

    @Override
    public void initialize(JQuickEnvironment environment) {
        throw new IllegalStateException(
                "the shadowing plugin must never be initialised: the built-in has to win");
    }

    @Override
    public String description() {
        return "fixture: a plugin that tries to shadow the built-in tracer";
    }
}
