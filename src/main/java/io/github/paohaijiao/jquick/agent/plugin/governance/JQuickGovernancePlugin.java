package io.github.paohaijiao.jquick.agent.plugin.governance;

import io.github.paohaijiao.jquick.agent.core.*;
import io.github.paohaijiao.jquick.agent.support.JQuickLogger;

import java.util.Collections;
import java.util.List;

/**
 * Capability: method level rate limiting, circuit breaking, blacklisting and authorisation.
 *
 * <p>Everything here is enforced on the real method, with no proxy, no annotation and no interceptor
 * registration. That is the practical difference from a framework-level gateway: it also works for
 * calls that never cross an HTTP boundary — an internal scheduler, an RPC consumer, a private service
 * method — which is where a Spring interceptor cannot reach.
 *
 * <p>Each of the four policies has an independent switch:
 *
 * <table>
 *   <tr><th>Setting</th><th>Effect when unset</th></tr>
 *   <tr><td>{@code rate.limit.qps}</td><td>no rate limiting</td></tr>
 *   <tr><td>{@code circuit.failure.rate}</td><td>no circuit breaking</td></tr>
 *   <tr><td>{@code governance.blacklist}</td><td>no blacklist</td></tr>
 *   <tr><td>{@code governance.auth}</td><td>no authorisation</td></tr>
 * </table>
 *
 * @author Martin
 */
public final class JQuickGovernancePlugin implements JQuickPlugin {

    public static final String NAME = "governance";

    private JQuickAuthorizer authorizer;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public JQuickCategory category() {
        return JQuickCategory.GOVERNANCE;
    }

    @Override
    public boolean isEnabled(JQuickConfig config) {
        return config.getBoolean(JQuickConfig.GOVERNANCE_ENABLED, false);
    }

    /**
     * Supplies the application's authorisation model. Must be called before the agent is installed.
     */
    public void setAuthorizer(JQuickAuthorizer authorizer) {
        this.authorizer = authorizer;
    }

    @Override
    public void initialize(JQuickEnvironment environment) {
        JQuickConfig config = environment.getConfig();
        JQuickGovernanceMetrics metrics = new JQuickGovernanceMetrics(environment.getMeters());

        double qps = config.getDouble(JQuickConfig.RATE_LIMIT_QPS, 0d);
        JQuickRateLimiter rateLimiter = qps > 0d
                ? new JQuickRateLimiter(qps, config.getInt(JQuickConfig.RATE_LIMIT_BURST, (int) qps))
                : null;

        double failureRate = config.getDouble(JQuickConfig.CIRCUIT_FAILURE_RATE, 0d);
        JQuickCircuitBreaker circuitBreaker = failureRate > 0d
                ? new JQuickCircuitBreaker(failureRate,
                config.getInt(JQuickConfig.CIRCUIT_WINDOW, 20),
                config.getInt("circuit.minimum.samples", 5),
                config.getLong(JQuickConfig.CIRCUIT_OPEN_MS, 10_000L))
                : null;

        if (rateLimiter == null && circuitBreaker == null && authorizer == null
                && config.getList(JQuickConfig.GOVERNANCE_BLACKLIST).isEmpty()) {
            JQuickLogger.warn("governance plugin is enabled but no policy is configured "
                    + "(rate.limit.qps / circuit.failure.rate / governance.blacklist / "
                    + "governance.auth); the gate is installed but never rejects");
        }

        environment.addGate(new JQuickGovernanceGate(config, authorizer, rateLimiter,
                circuitBreaker, metrics));
        if (circuitBreaker != null || config.getBoolean("governance.metrics", true)) {
            environment.addListener(new JQuickGovernanceListener(circuitBreaker, metrics));
        }
    }

    @Override
    public List<JQuickClassEnhancer> enhancers() {
        // Purely runtime: enforcement rides on the hooks the tracing plugin already injected.
        return Collections.emptyList();
    }

    @Override
    public String description() {
        return "rate limit, circuit breaker, blacklist, authorisation";
    }
}
