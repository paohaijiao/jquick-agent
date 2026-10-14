package io.github.paohaijiao.jquick.agent.plugin.mock;

import io.github.paohaijiao.jquick.agent.core.*;

import java.util.Collections;
import java.util.List;

/**
 * Capability: byte-code level stubbing of external dependencies.
 *
 * <p>Designed for integration tests that would otherwise need a database, a broker or a third-party
 * endpoint: start the JVM with the agent and {@code mock.enabled=true}, register the stubs, and every
 * instrumented call to a stubbed method is answered locally.
 *
 * <p><b>The switch is read at instrumentation time.</b> The stub check is a branch injected into the
 * method head, so it only exists in classes that were instrumented while mocking was enabled.
 * Enabling the plugin later affects only classes loaded afterwards — an honest consequence of "we
 * add instructions, we never re-architect a class", and the reason the README tells users to enable
 * it before the code under test is loaded.
 *
 * @author Martin
 */
public final class JQuickMockPlugin implements JQuickPlugin {

    public static final String NAME = "mock";

    private final JQuickMockRegistry registry = new JQuickMockRegistry();

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
        return config.getBoolean(JQuickConfig.MOCK_ENABLED, false);
    }

    @Override
    public void initialize(JQuickEnvironment environment) {
        environment.addStubResolver(registry);
    }

    /**
     * The registry to declare stubs against.
     */
    public JQuickMockRegistry getRegistry() {
        return registry;
    }

    @Override
    public List<JQuickClassEnhancer> enhancers() {
        return Collections.emptyList();
    }

    @Override
    public String description() {
        return "byte-code level stubbing of dependencies";
    }
}
