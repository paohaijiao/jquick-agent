package io.github.paohaijiao.jquick.agent.plugin.governance;

import io.github.paohaijiao.jquick.agent.core.JQuickMethodEvent;
import io.github.paohaijiao.jquick.agent.core.JQuickMethodListener;
import io.github.paohaijiao.jquick.agent.core.JQuickRejectedException;
import io.github.paohaijiao.jquick.agent.trace.JQuickSpan;

/**
 * Feeds the circuit breaker with the outcome of every completed call.
 *
 * <p>The split between {@link JQuickGovernanceGate} and this listener mirrors the split between
 * deciding and observing: the gate runs <em>before</em> the body and answers "may this run?", while
 * the breaker can only be updated <em>after</em> the body, from the outcome. Two extension points,
 * one policy.
 *
 * <p>A call that was itself rejected by governance is explicitly excluded from the failure count.
 * Counting it would make the breaker trip on its own rejections — the classic feedback loop where a
 * rate limit escalates into a full circuit opening.
 *
 * @author Martin
 */
public final class JQuickGovernanceListener implements JQuickMethodListener {

    private final JQuickCircuitBreaker circuitBreaker;

    private final JQuickGovernanceMetrics metrics;

    public JQuickGovernanceListener(JQuickCircuitBreaker circuitBreaker, JQuickGovernanceMetrics metrics) {
        this.circuitBreaker = circuitBreaker;
        this.metrics = metrics;
    }

    @Override
    public void onMethodEvent(JQuickMethodEvent event) {
        if (event.getPhase() == JQuickMethodEvent.JQuickPhase.ENTER) {
            return;
        }
        JQuickSpan span = event.getSpan();
        if (span == null) {
            return;
        }
        String key = span.getOperation();
        try {
            if (event.getPhase() == JQuickMethodEvent.JQuickPhase.EXIT) {
                if (circuitBreaker != null) {
                    circuitBreaker.recordSuccess(key);
                }
                if (metrics != null) {
                    metrics.recordCall(key, true);
                }
                return;
            }
            Throwable error = event.getError();
            if (error instanceof JQuickRejectedException) {
                // Self-inflicted: never let a rejection feed the breaker.
                return;
            }
            if (circuitBreaker != null) {
                circuitBreaker.recordFailure(key);
            }
            if (metrics != null) {
                metrics.recordCall(key, false);
            }
        } catch (Throwable ignored) {
            // Policy bookkeeping must never affect the application.
        }
    }

    @Override
    public String name() {
        return "governance-outcomes";
    }
}
