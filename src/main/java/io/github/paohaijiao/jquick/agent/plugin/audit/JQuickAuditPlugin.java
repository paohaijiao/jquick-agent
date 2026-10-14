package io.github.paohaijiao.jquick.agent.plugin.audit;

import io.github.paohaijiao.jquick.agent.core.*;
import io.github.paohaijiao.jquick.agent.trace.JQuickSpanType;

import java.util.ArrayList;
import java.util.List;

/**
 * Capability: security audit trail for the selected operations.
 *
 * <p>Point it at the operations that change state or expose personal data; every invocation then
 * produces a record containing the caller, the operation, the arguments and the outcome, in addition
 * to a Prometheus counter that makes "how much privileged traffic is there" observable.
 *
 * <p>The principal is resolved through a {@link JQuickPrincipalResolver} supplied by the application,
 * because only the application knows where its authentication context lives.
 *
 * @author Martin
 */
public final class JQuickAuditPlugin implements JQuickPlugin {

    public static final String NAME = "audit";
    private final List<JQuickClassEnhancer> enhancers = new ArrayList<JQuickClassEnhancer>();
    private JQuickPrincipalResolver principalResolver;

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
        return config.getBoolean(JQuickConfig.AUDIT_ENABLED, false);
    }

    /**
     * Supplies the application's authentication context. Must be called before installation when
     * audit is enabled.
     */
    public void setPrincipalResolver(JQuickPrincipalResolver principalResolver) {
        this.principalResolver = principalResolver;
    }

    @Override
    public void initialize(JQuickEnvironment environment) {
        JQuickConfig config = environment.getConfig();
        boolean includeArguments = config.getBoolean("audit.args", true);
        environment.addListener(new JQuickAuditListener(principalResolver, environment.getMeters(),
                config.getInt("audit.arg.length", 128), includeArguments));
        enhancers.add(new JQuickInstrumentEnhancer(
                NAME,
                JQuickClassMatcher.byName(config.getList(JQuickConfig.AUDIT_INCLUDES)
                        .toArray(new String[0])),
                JQuickMethodMatcher.all(),
                JQuickSpanType.LOCAL,
                NAME,
                environment,
                includeArguments ? JQuickHookPlan.builder().captureResult().build() : null));
    }

    @Override
    public List<JQuickClassEnhancer> enhancers() {
        return enhancers;
    }

    @Override
    public String description() {
        return "audit trail: caller, operation, arguments, outcome";
    }
}
