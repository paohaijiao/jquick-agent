package io.github.paohaijiao.jquick.agent.trace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable copy of a {@link JQuickTraceScope}, used to hand a trace context over to another thread.
 *
 * <p>The whole span stack is carried over — not only the trace id — so a task submitted to a thread
 * pool continues the originating call tree instead of starting a detached branch.
 *
 * @author Martin
 */
public final class JQuickTraceSnapshot {

    private final String traceId;

    private final List<String> spanStack;

    private final boolean sampled;

    JQuickTraceSnapshot(String traceId, List<String> spanStack, boolean sampled) {
        this.traceId = traceId;
        this.spanStack = spanStack == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(spanStack));
        this.sampled = sampled;
    }

    public String getTraceId() {
        return traceId;
    }

    /**
     * Span ids from the innermost to the outermost.
     */
    public List<String> getSpanStack() {
        return spanStack;
    }

    public boolean isSampled() {
        return sampled;
    }

    public boolean isActive() {
        return traceId != null;
    }

    @Override
    public String toString() {
        return "JQuickTraceSnapshot{traceId=" + traceId + ", depth=" + spanStack.size() + '}';
    }
}
