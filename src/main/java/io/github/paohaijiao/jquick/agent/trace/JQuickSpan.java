package io.github.paohaijiao.jquick.agent.trace;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable snapshot of one completed span.
 *
 * <p>A span is produced by the runtime sink when an instrumented method returns or throws, and it is
 * the unit the listeners, the metrics registry and the topology builder all consume. Keeping it
 * immutable and allocation-cheap matters: one instance is created per instrumented call.
 *
 * @author Martin
 */
public final class JQuickSpan {

    private final String traceId;

    private final String spanId;

    private final String parentSpanId;

    private final JQuickSpanType type;

    private final String component;

    private final String operation;

    private final String className;

    private final String methodName;

    private final String threadName;

    private final int depth;

    private final long startNanos;

    private final long durationNanos;

    private final boolean success;

    private final String errorType;

    private final String errorMessage;

    private final Map<String, String> tags;

    private JQuickSpan(Builder builder) {
        this.traceId = builder.traceId;
        this.spanId = builder.spanId;
        this.parentSpanId = builder.parentSpanId;
        this.type = builder.type;
        this.component = builder.component;
        this.operation = builder.operation;
        this.className = builder.className;
        this.methodName = builder.methodName;
        this.threadName = builder.threadName;
        this.depth = builder.depth;
        this.startNanos = builder.startNanos;
        this.durationNanos = builder.durationNanos;
        this.success = builder.success;
        this.errorType = builder.errorType;
        this.errorMessage = builder.errorMessage;
        this.tags = builder.tags.isEmpty()
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(builder.tags));
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getTraceId() {
        return traceId;
    }

    public String getSpanId() {
        return spanId;
    }

    public String getParentSpanId() {
        return parentSpanId;
    }

    public JQuickSpanType getType() {
        return type;
    }

    /**
     * Logical component the span belongs to, e.g. {@code jdbc}, {@code redis}, or {@code method}.
     */
    public String getComponent() {
        return component;
    }

    /**
     * Readable operation, e.g. {@code com.demo.OrderService#pay(java.lang.String, long)}.
     */
    public String getOperation() {
        return operation;
    }

    public String getClassName() {
        return className;
    }

    public String getMethodName() {
        return methodName;
    }

    public String getThreadName() {
        return threadName;
    }

    public int getDepth() {
        return depth;
    }

    public long getStartNanos() {
        return startNanos;
    }

    public long getDurationNanos() {
        return durationNanos;
    }

    public long getDurationMillis() {
        return durationNanos / 1_000_000L;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getErrorType() {
        return errorType;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    /**
     * Renders the span as a single, greppable log line.
     */
    public String format() {
        StringBuilder sb = new StringBuilder(160);
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
        sb.append(type == JQuickSpanType.LOCAL ? "|-" : (type == JQuickSpanType.ENTRY ? ">>" : "<<"));
        sb.append(' ').append(operation)
                .append(" [").append(getDurationMillis()).append("ms]");
        if (component != null) {
            sb.append(" component=").append(component);
        }
        if (!success) {
            sb.append(" ERROR=").append(errorType);
            if (errorMessage != null) {
                sb.append(": ").append(errorMessage);
            }
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "JQuickSpan{traceId=" + traceId
                + ", spanId=" + spanId
                + ", parent=" + parentSpanId
                + ", type=" + type
                + ", operation=" + operation
                + ", durationNanos=" + durationNanos
                + ", success=" + success + '}';
    }

    /**
     * Mutable builder; one instance is reused per call by the runtime sink to avoid allocation of
     * intermediate objects.
     */
    public static final class Builder {

        private final Map<String, String> tags = new LinkedHashMap<String, String>();
        private String traceId;
        private String spanId;
        private String parentSpanId;
        private JQuickSpanType type = JQuickSpanType.LOCAL;
        private String component;
        private String operation;
        private String className;
        private String methodName;
        private String threadName;
        private int depth;
        private long startNanos;
        private long durationNanos;
        private boolean success = true;
        private String errorType;
        private String errorMessage;

        public Builder traceId(String value) {
            this.traceId = value;
            return this;
        }

        public Builder spanId(String value) {
            this.spanId = value;
            return this;
        }

        public Builder parentSpanId(String value) {
            this.parentSpanId = value;
            return this;
        }

        public Builder type(JQuickSpanType value) {
            this.type = value == null ? JQuickSpanType.LOCAL : value;
            return this;
        }

        public Builder component(String value) {
            this.component = value;
            return this;
        }

        public Builder operation(String value) {
            this.operation = value;
            return this;
        }

        public Builder className(String value) {
            this.className = value;
            return this;
        }

        public Builder methodName(String value) {
            this.methodName = value;
            return this;
        }

        public Builder threadName(String value) {
            this.threadName = value;
            return this;
        }

        public Builder depth(int value) {
            this.depth = value;
            return this;
        }

        public Builder startNanos(long value) {
            this.startNanos = value;
            return this;
        }

        public Builder durationNanos(long value) {
            this.durationNanos = value;
            return this;
        }

        public Builder success(boolean value) {
            this.success = value;
            return this;
        }

        public Builder errorType(String value) {
            this.errorType = value;
            return this;
        }

        public Builder errorMessage(String value) {
            this.errorMessage = value;
            return this;
        }

        public Builder tag(String key, String value) {
            if (key != null && value != null) {
                this.tags.put(key, value);
            }
            return this;
        }

        public JQuickSpan build() {
            return new JQuickSpan(this);
        }
    }
}
