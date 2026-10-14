package io.github.paohaijiao.jquick.agent.core;

import java.util.Collections;
import java.util.List;

/**
 * Root extension point: a self-contained capability.
 *
 * <p>The whole framework is an empty kernel plus a list of plugins. Everything an operator can switch
 * on — tracing, JDBC metrics, RASP, mocking, coverage — is one implementation of this interface, and
 * the kernel itself has no knowledge of any of them. Adding a capability means writing a plugin and
 * registering it (programmatically or through {@code META-INF/services}), never editing the kernel.
 *
 * <p>Lifecycle: {@link #isEnabled(JQuickConfig)} is asked first, then {@link #initialize} is called
 * exactly once on the thread that installs the agent, and the enhancers returned by
 * {@link #enhancers()} are consulted for every class the transformer sees. A plugin that returns
 * {@code false} from {@code isEnabled} is never initialised and contributes nothing — not even a
 * class-loading cost.
 *
 * @author Martin
 */
public interface JQuickPlugin {

    /**
     * Stable identifier, also used as the {@code <name>.enabled} configuration prefix.
     */
    String name();

    /**
     * Functional family, used for reporting and bulk switches.
     */
    JQuickCategory category();

    /**
     * Whether this plugin should take part in the current run.
     */
    boolean isEnabled(JQuickConfig config);

    /**
     * Registers listeners, gates, transformers and any other runtime hook.
     *
     * <p>Must be fast and must not throw: it runs during agent installation, i.e. before the host
     * application's {@code main}. It is also the only point where a plugin may acquire the runtime
     * registries (meters, topology, method registry) it will need later.
     */
    void initialize(JQuickEnvironment environment);

    /**
     * Byte-code contributions of this plugin. The default is "none", which is the right answer for
     * the many capabilities that are purely runtime (audit sinks, metric exporters, governance).
     */
    default List<JQuickClassEnhancer> enhancers() {
        return Collections.emptyList();
    }

    /**
     * Human readable description shown in the bootstrap report.
     */
    default String description() {
        return name();
    }
}
