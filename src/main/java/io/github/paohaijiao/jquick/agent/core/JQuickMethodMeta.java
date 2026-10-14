package io.github.paohaijiao.jquick.agent.core;

import io.github.paohaijiao.jquick.agent.trace.JQuickSpanType;

/**
 * Runtime metadata attached to one instrumented method.
 *
 * <p>Instances are created at <b>instrumentation</b> time (in the transformer) and looked up by the
 * runtime sink from the pre-computed readable signature. Injecting a small table index instead of
 * re-deriving class, method and component on every call is what keeps the injected byte-code to a
 * single string constant.
 *
 * <p>Two plugins may tag the same method (for example the generic tracer marks it {@code LOCAL}
 * while the JDBC plugin marks it {@code EXIT}). Registration therefore <em>merges</em> rather than
 * overwrites, and the most specific span type wins.
 *
 * @author Martin
 */
public final class JQuickMethodMeta {

    private final String signature;

    private final String className;

    private final String methodName;

    private final String descriptor;

    private final int access;

    private JQuickSpanType spanType;

    private String component;

    public JQuickMethodMeta(String signature, String className, String methodName,
                            String descriptor, int access, JQuickSpanType spanType,
                            String component) {
        this.signature = signature;
        this.className = className;
        this.methodName = methodName;
        this.descriptor = descriptor;
        this.access = access;
        this.spanType = spanType == null ? JQuickSpanType.LOCAL : spanType;
        this.component = component;
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

    public int getAccess() {
        return access;
    }

    public JQuickSpanType getSpanType() {
        return spanType;
    }

    public String getComponent() {
        return component;
    }

    /**
     * Merges a later registration into this one, keeping the most specific span type.
     */
    synchronized void merge(JQuickMethodMeta other) {
        if (other.spanType != null && other.spanType.priority() > this.spanType.priority()) {
            this.spanType = other.spanType;
        }
        if (other.component != null) {
            if (this.component == null) {
                this.component = other.component;
            } else if (!this.component.contains(other.component)) {
                this.component = this.component + "," + other.component;
            }
        }
    }

    @Override
    public String toString() {
        return "JQuickMethodMeta{signature=" + signature
                + ", type=" + spanType
                + ", component=" + component + '}';
    }
}
