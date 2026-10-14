package io.github.paohaijiao.jquick.agent.plugin.security;

import io.github.paohaijiao.jquick.agent.core.*;

import java.util.Collections;
import java.util.List;

/**
 * Capability: runtime application self-protection.
 *
 * <p>The plugin is almost entirely configuration: it reads the rule set, publishes it to the
 * {@link JQuickSecurityGuard} and installs a gate. Like governance, it injects no byte-code of its
 * own — it rides on the arguments the tracing plugin already captures, which is why enabling RASP has
 * no additional instrumentation cost.
 *
 * <p>Set {@code security.enforce=false} to run the rules in <b>dry-run mode</b>: violations are
 * logged and counted but never blocked. That is the only responsible way to introduce a blocking
 * security rule into an existing production system.
 *
 * @author Martin
 */
public final class JQuickSecurityPlugin implements JQuickPlugin {

    public static final String NAME = "security";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public JQuickCategory category() {
        return JQuickCategory.SECURITY;
    }

    @Override
    public boolean isEnabled(JQuickConfig config) {
        return config.getBoolean(JQuickConfig.SECURITY_ENABLED, false);
    }

    @Override
    public void initialize(JQuickEnvironment environment) {
        JQuickSecurityGuard.configure(environment.getConfig());
        environment.addGate(new JQuickSecurityGate());
    }

    @Override
    public List<JQuickClassEnhancer> enhancers() {
        return Collections.emptyList();
    }

    @Override
    public String description() {
        return "RASP: SQL injection, command injection, dangerous deserialisation";
    }
}
