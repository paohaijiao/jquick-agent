package io.github.paohaijiao.jquick.agent.trace;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Per-thread trace state: the current trace id plus the stack of active spans.
 *
 * <h3>Why a stack and not just a "current span"</h3>
 * A flat "current span" cannot answer the two questions the agent actually has to answer:
 * <ol>
 *   <li><em>Who is my parent?</em> — the top of the stack, which yields the call tree;</li>
 *   <li><em>How deep am I?</em> — the stack size, which is used to render the trace and to bound
 *       recursion in the output.</li>
 * </ol>
 *
 * <h3>Thread pools</h3>
 * The scope lives in a {@link ThreadLocal}, so a pooled worker thread does not inherit the caller's
 * trace. {@link #snapshot()} copies <b>the whole stack</b>, not just the trace id, so a worker that
 * restores it produces children attached to the correct parent instead of a flattened trace rooted
 * at the pool.
 *
 * @author Martin
 */
public final class JQuickTraceScope {

    /**
     * Hard cap on nesting depth. A deeper stack almost always means a frame imbalance caused by an
     * exception that crossed an uninstrumented boundary, and it must not grow without bound.
     */
    private static final int MAX_DEPTH = 128;
    private final Deque<String> spanStack = new ArrayDeque<String>();
    private String traceId;
    private boolean sampled = true;

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public boolean isSampled() {
        return sampled;
    }

    public void setSampled(boolean sampled) {
        this.sampled = sampled;
    }

    /**
     * Whether a trace has been started on this thread.
     */
    public boolean isActive() {
        return traceId != null;
    }

    /**
     * The span that is currently executing, or {@code null} when the thread is not inside any span.
     */
    public String currentSpanId() {
        return spanStack.peek();
    }

    /**
     * The parent of the currently executing span, i.e. the second entry on the stack.
     *
     * <p>{@code null} for a root span.
     */
    public String parentOfCurrentSpan() {
        java.util.Iterator<String> it = spanStack.iterator();
        if (!it.hasNext()) {
            return null;
        }
        it.next();
        return it.hasNext() ? it.next() : null;
    }

    /**
     * Pushes a new span and returns its parent id (may be {@code null} for a root span).
     */
    public String beginSpan(String spanId) {
        String parent = spanStack.peek();
        if (spanStack.size() >= MAX_DEPTH) {
            // Guard against pathological growth: drop the oldest frame, keeping the newest ones so
            // that the closest parent is still correct.
            spanStack.pollLast();
        }
        spanStack.push(spanId);
        return parent;
    }

    /**
     * Pops the innermost span. Mirrors {@link #beginSpan(String)} and is therefore also used to
     * unwind stale frames when an exception crossed an uninstrumented boundary.
     *
     * @param spanId the span expected on the top of the stack; when it does not match, frames are
     *               unwound until it is found so the bookkeeping self-heals
     */
    public void endSpan(String spanId) {
        if (spanStack.isEmpty()) {
            return;
        }
        if (spanId == null) {
            spanStack.poll();
            return;
        }
        while (!spanStack.isEmpty()) {
            String top = spanStack.poll();
            if (spanId.equals(top)) {
                return;
            }
        }
    }

    /**
     * Elapsed-nanos bookkeeping is kept in the sink; this only exposes the depth for rendering.
     */
    public int depth() {
        return spanStack.size();
    }

    /**
     * Clears all thread state. Called when a trace completes, so pooled threads do not retain a
     * finished trace and leak a wrong trace id into the next task.
     */
    public void reset() {
        traceId = null;
        spanStack.clear();
        sampled = true;
    }

    /**
     * Captures an immutable copy of this scope for cross-thread propagation.
     */
    public JQuickTraceSnapshot snapshot() {
        List<String> stack = new ArrayList<String>(spanStack.size());
        // ArrayDeque iterates from the top of the stack, which is exactly the order we want to
        // restore (push order is reversed on restore).
        for (String spanId : spanStack) {
            stack.add(spanId);
        }
        return new JQuickTraceSnapshot(traceId, stack, sampled);
    }

    /**
     * Overwrites this scope with a captured snapshot.
     */
    public void restore(JQuickTraceSnapshot snapshot) {
        spanStack.clear();
        if (snapshot == null) {
            traceId = null;
            sampled = true;
            return;
        }
        traceId = snapshot.getTraceId();
        sampled = snapshot.isSampled();
        List<String> stack = snapshot.getSpanStack();
        // The snapshot is stored top-first; re-push in reverse so the original order is preserved.
        for (int i = stack.size() - 1; i >= 0; i--) {
            spanStack.push(stack.get(i));
        }
    }

    @Override
    public String toString() {
        return "trace=" + traceId + ", spans=" + spanStack.size();
    }
}
