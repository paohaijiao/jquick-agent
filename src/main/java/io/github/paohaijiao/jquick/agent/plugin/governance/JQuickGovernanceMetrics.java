package io.github.paohaijiao.jquick.agent.plugin.governance;

import io.github.paohaijiao.jquick.agent.metrics.JQuickMeterRegistry;

/**
 * Metrics emitted by the admission-control layer.
 *
 * <p>A rejection counter alone is not enough to run this in production: the number that decides
 * whether a policy is calibrated correctly is the <em>ratio</em> of rejections to admissions, which
 * is why the gate counts both. A rate limiter that rejects 40% of legitimate traffic looks identical
 * to a healthy one until that ratio is on a dashboard.
 *
 * @author Martin
 */
public final class JQuickGovernanceMetrics {

    private final JQuickMeterRegistry meters;

    public JQuickGovernanceMetrics(JQuickMeterRegistry meters) {
        this.meters = meters;
    }

    public void recordRejection(String policy, String subject) {
        if (meters == null) {
            return;
        }
        try {
            meters.counter("jquick_governance_rejections_total", "policy", policy).increment();
        } catch (Throwable ignored) {
            // Metrics must never affect enforcement.
        }
    }

    public void recordCall(String signature, boolean success) {
        if (meters == null) {
            return;
        }
        try {
            meters.counter("jquick_governance_calls_total", "outcome", success ? "ok" : "error")
                    .increment();
        } catch (Throwable ignored) {
            // Metrics must never affect enforcement.
        }
    }
}
