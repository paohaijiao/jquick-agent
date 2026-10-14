package io.github.paohaijiao.jquick.agent.core;

import io.github.paohaijiao.jquick.agent.trace.JQuickSpanType;

/**
 * Mutable state of one in-flight instrumented call.
 *
 * <p>One instance is created per call by {@link io.github.paohaijiao.jquick.agent.runtime.JQuickSink},
 * pushed on the thread frame stack, and discarded when the method returns or throws. It is the
 * single object the gates, the argument transformers, the result transformers and the listeners all
 * operate on, which is why it is mutable: an interceptor can replace {@link #getArguments()
 * arguments} and the same instance is then observed by every downstream listener.
 *
 * <p>It is intentionally <b>not</b> thread safe — a call belongs to exactly one thread.
 *
 * @author Martin
 */
public final class JQuickInvocation {

    private final String signature;

    private final String className;

    private final String methodName;

    private final String descriptor;

    private final JQuickSpanType spanType;

    private final String component;

    private final String threadName;

    private final long startNanos;

    private String traceId;

    private String spanId;

    private String parentSpanId;

    private Object[] arguments;

    /**
     * The value the caller finally observed, i.e. <em>after</em> any result transformation.
     *
     * <p>Recorded here rather than passed to the listener separately because a listener is handed a
     * {@link JQuickMethodEvent} and nothing else — and a capability such as the audit sink needs to
     * know what actually left the method, not what the method internally computed. It stays
     * {@code null} for a void method, for a call that failed, and for a call that a gate rejected.
     */
    private Object returnValue;

    private int depth;

    public JQuickInvocation(JQuickMethodMeta meta, String threadName, long startNanos) {
        this.signature = meta.getSignature();
        this.className = meta.getClassName();
        this.methodName = meta.getMethodName();
        this.descriptor = meta.getDescriptor();
        this.spanType = meta.getSpanType();
        this.component = meta.getComponent();
        this.threadName = threadName;
        this.startNanos = startNanos;
    }

    public String getSignature() {
        return signature;
    }

    public String getClassName() {
        return className;
    }

    public String getMethodName() {
        return methodName;
    }

    public String getDescriptor() {
        return descriptor;
    }

    public JQuickSpanType getSpanType() {
        return spanType;
    }

    public String getComponent() {
        return component;
    }

    public String getThreadName() {
        return threadName;
    }

    public long getStartNanos() {
        return startNanos;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getSpanId() {
        return spanId;
    }

    public void setSpanId(String spanId) {
        this.spanId = spanId;
    }

    public String getParentSpanId() {
        return parentSpanId;
    }

    public void setParentSpanId(String parentSpanId) {
        this.parentSpanId = parentSpanId;
    }

    /**
     * Captured arguments, or {@code null} when argument capture is disabled for this method.
     */
    public Object[] getArguments() {
        return arguments;
    }

    public void setArguments(Object[] arguments) {
        this.arguments = arguments;
    }

    /**
     * The value the caller observed, or {@code null} for a void, failed or rejected call.
     */
    public Object getReturnValue() {
        return returnValue;
    }

    public void setReturnValue(Object returnValue) {
        this.returnValue = returnValue;
    }

    public int getDepth() {
        return depth;
    }

    public void setDepth(int depth) {
        this.depth = depth;
    }

    /**
     * Resolves the argument at {@code index}, or {@code null} when it was not captured.
     */
    public Object getArgument(int index) {
        if (arguments == null || index < 0 || index >= arguments.length) {
            return null;
        }
        return arguments[index];
    }

    /**
     * Resolves the first argument assignable to {@code type}; used by the component plugins to pick
     * up an SQL string or an HTTP request object without knowing the exact signature.
     */
    public Object findArgument(Class<?> type) {
        if (arguments == null || type == null) {
            return null;
        }
        for (Object argument : arguments) {
            if (type.isInstance(argument)) {
                return argument;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return "JQuickInvocation{trace=" + traceId
                + ", span=" + spanId
                + ", operation=" + signature
                + ", type=" + spanType
                + ", component=" + component + '}';
    }
}
