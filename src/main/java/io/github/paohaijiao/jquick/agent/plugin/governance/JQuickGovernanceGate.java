package io.github.paohaijiao.jquick.agent.plugin.governance;

import io.github.paohaijiao.jquick.agent.core.JQuickConfig;
import io.github.paohaijiao.jquick.agent.core.JQuickInvocation;
import io.github.paohaijiao.jquick.agent.core.JQuickInvocationGate;
import io.github.paohaijiao.jquick.agent.core.JQuickRejectedException;
import io.github.paohaijiao.jquick.agent.support.JQuickClassFilters;
import io.github.paohaijiao.jquick.agent.support.JQuickLogger;

import java.util.List;

/**
 * Admission control at the method boundary: blacklist, authorisation, rate limiting, circuit
 * breaking — in that order, cheapest and most decisive first.
 *
 * <p>The ordering is not arbitrary. A blacklisted operation must be refused before anything else is
 * evaluated; an unauthorised caller must not be able to consume the rate-limit budget of a
 * legitimate one; and a rate-limited call must not be counted as a failure by the circuit breaker.
 *
 * <p><b>This gate changes application behaviour.</b> A rejection throws
 * {@link JQuickRejectedException} from the instrumented method before its body runs. It is therefore
 * disabled by default and every rejection is counted and logged, so an operator can see exactly what
 * the policy is doing before trusting it.
 *
 * @author Martin
 */
public final class JQuickGovernanceGate implements JQuickInvocationGate {

    private final List<String> blacklistPatterns;

    private final JQuickAuthorizer authorizer;

    private final JQuickRateLimiter rateLimiter;

    private final JQuickCircuitBreaker circuitBreaker;

    private final JQuickGovernanceMetrics metrics;

    public JQuickGovernanceGate(JQuickConfig config, JQuickAuthorizer authorizer,
                                JQuickRateLimiter rateLimiter, JQuickCircuitBreaker circuitBreaker,
                                JQuickGovernanceMetrics metrics) {
        this.blacklistPatterns = config.getList(JQuickConfig.GOVERNANCE_BLACKLIST);
        this.authorizer = authorizer;
        this.rateLimiter = rateLimiter;
        this.circuitBreaker = circuitBreaker;
        this.metrics = metrics;
    }

    @Override
    public boolean requiresArguments() {
        return false;
    }

    @Override
    public void inspect(JQuickInvocation invocation) throws JQuickRejectedException {
        String signature = invocation.getSignature();
        if (isBlacklisted(signature)) {
            reject("governance.blacklist", signature, "operation is blacklisted: " + signature);
        }
        if (authorizer != null) {
            String principal = safePrincipal();
            if (!authorizer.isAllowed(principal, signature)) {
                reject("governance.auth", signature,
                        "principal '" + principal + "' is not allowed to call " + signature);
            }
        }
        if (rateLimiter != null && !rateLimiter.tryAcquire(signature)) {
            reject("governance.rate-limit", signature,
                    "rate limit exceeded for " + signature);
        }
        if (circuitBreaker != null && !circuitBreaker.allowRequest(signature)) {
            reject("governance.circuit-breaker", signature,
                    "circuit is open for " + signature);
        }
    }

    private boolean isBlacklisted(String signature) {
        if (blacklistPatterns.isEmpty()) {
            return false;
        }
        for (String pattern : blacklistPatterns) {
            if (signature.startsWith(pattern) || signature.contains(pattern)
                    || JQuickClassFilters.matchesAnyPattern(signature, java.util.Collections.singletonList(pattern))) {
                return true;
            }
        }
        return false;
    }

    private String safePrincipal() {
        try {
            String principal = authorizer.currentPrincipal();
            return principal == null ? "anonymous" : principal;
        } catch (Throwable ex) {
            JQuickLogger.suppressed("authorizer failed to resolve the principal", ex);
            return "unknown";
        }
    }

    private void reject(String policy, String subject, String message) {
        if (metrics != null) {
            metrics.recordRejection(policy, subject);
        }
        JQuickLogger.warn("rejected by " + policy + ": " + message);
        throw new JQuickRejectedException(policy, subject, message);
    }

    @Override
    public String name() {
        return "governance";
    }
}
