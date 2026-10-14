package io.github.paohaijiao.jquick.agent.plugin.audit;

import io.github.paohaijiao.jquick.agent.core.JQuickInvocation;
import io.github.paohaijiao.jquick.agent.core.JQuickMethodEvent;
import io.github.paohaijiao.jquick.agent.core.JQuickMethodListener;
import io.github.paohaijiao.jquick.agent.core.JQuickPrincipalResolver;
import io.github.paohaijiao.jquick.agent.metrics.JQuickMeterRegistry;
import io.github.paohaijiao.jquick.agent.support.JQuickValueFormatter;
import io.github.paohaijiao.jquick.agent.trace.JQuickSpan;

/**
 * Writes one audit record per completed operation: who called what, when, with which arguments, and
 * how it ended.
 *
 * <p>An audit record is required to answer three questions after the fact — <em>was this action
 * authorised</em>, <em>what exactly was requested</em>, <em>did it succeed</em> — so unlike a trace it
 * must not be sampled, and unlike a metric it must not be aggregated.
 *
 * <p><b>Arguments are truncated, never omitted.</b> An audit log without the payload answers none of
 * the questions it exists for; a log with an unbounded payload is a denial-of-service vector. A
 * configurable cap ({@code audit.arg.length}) is the compromise, and the truncation is made visible
 * in the record so a reader knows the value was clipped.
 *
 * @author Martin
 */
public final class JQuickAuditListener implements JQuickMethodListener {

    private final JQuickPrincipalResolver principalResolver;

    private final JQuickMeterRegistry meters;

    private final int maxArgumentLength;

    private final boolean includeArguments;

    public JQuickAuditListener(JQuickPrincipalResolver principalResolver, JQuickMeterRegistry meters, int maxArgumentLength, boolean includeArguments) {
        this.principalResolver = principalResolver;
        this.meters = meters;
        this.maxArgumentLength = maxArgumentLength <= 0 ? 128 : maxArgumentLength;
        this.includeArguments = includeArguments;
    }

    @Override
    public boolean captureArguments() {
        return includeArguments;
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
        try {
            String outcome = span.isSuccess() ? "OK" : "ERROR";
            StringBuilder record = new StringBuilder(256);
            record.append("[jquick-agent] AUDIT ")
                    .append("principal=").append(resolvePrincipal())
                    .append(" operation=").append(span.getOperation())
                    .append(" outcome=").append(outcome)
                    .append(" durationMs=").append(span.getDurationMillis())
                    .append(" thread=").append(span.getThreadName())
                    .append(" traceId=").append(span.getTraceId());
            if (!span.isSuccess()) {
                record.append(" error=").append(span.getErrorType());
            }
            if (includeArguments) {
                record.append(" args=").append(renderArguments(event.getInvocation()));
            }
            System.out.println(record);

            if (meters != null) {
                meters.counter("jquick_audit_records_total", "outcome", outcome).increment();
            }
        } catch (Throwable ignored) {
            // Auditing must never break the audited operation.
        }
    }

    private String resolvePrincipal() {
        if (principalResolver == null) {
            return "unknown";
        }
        try {
            String principal = principalResolver.currentPrincipal();
            return principal == null ? "anonymous" : principal;
        } catch (Throwable ex) {
            return "unknown";
        }
    }

    /**
     * The rendering lives in the shared formatter: the trace log prints the same arguments beside the
     * same span, and two copies of a truncation rule is two ways for one of them to flood a log file.
     */
    private String renderArguments(JQuickInvocation invocation) {
        return JQuickValueFormatter.arguments(invocation.getArguments(),
                JQuickValueFormatter.MAX_ARGUMENTS, maxArgumentLength);
    }

    @Override
    public String name() {
        return "audit";
    }
}
