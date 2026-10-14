package io.github.paohaijiao.jquick.agent.core;

/**
 * Describes which hooks the emitter must inject for a given enhancer.
 *
 * <p>This object is the single point where the cost of instrumentation is decided. Injecting an
 * argument array or boxing a return value is only acceptable when a listener actually consumes it,
 * so the plan is computed from the union of the capability flags declared by the registered
 * listeners and interceptors, and it is resolved <b>at instrumentation time</b>. A capability that
 * is switched off therefore costs exactly nothing at runtime — not even a branch.
 *
 * @author Martin
 */
public final class JQuickHookPlan {

    /**
     * Span only: entry, exit and error hooks, no payload. The cheapest useful plan.
     */
    public static final JQuickHookPlan OBSERVE = builder().build();

    private final boolean captureArguments;

    private final boolean transformArguments;

    private final boolean captureResult;

    private final boolean transformResult;

    private final boolean mockCheck;

    private final boolean wrapThreadPoolTask;

    private JQuickHookPlan(Builder builder) {
        this.transformArguments = builder.transformArguments;
        this.captureArguments = builder.captureArguments || builder.transformArguments;
        this.transformResult = builder.transformResult;
        this.captureResult = builder.captureResult || builder.transformResult;
        this.mockCheck = builder.mockCheck;
        this.wrapThreadPoolTask = builder.wrapThreadPoolTask;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Union of two plans: what either of them needs.
     *
     * <p>Used to combine the environment-wide requirement (the observers and the gates, which see
     * every instrumented call) with the requirement of the enhancer being applied. A capability that
     * transforms only its own classes therefore does not impose its cost on the rest of the
     * application.
     */
    public static JQuickHookPlan merge(JQuickHookPlan first, JQuickHookPlan second) {
        if (first == null) {
            return second == null ? OBSERVE : second;
        }
        if (second == null) {
            return first;
        }
        Builder builder = builder();
        if (first.captureArguments || second.captureArguments) {
            builder.captureArguments();
        }
        if (first.transformArguments || second.transformArguments) {
            builder.transformArguments();
        }
        if (first.captureResult || second.captureResult) {
            builder.captureResult();
        }
        if (first.transformResult || second.transformResult) {
            builder.transformResult();
        }
        if (first.mockCheck || second.mockCheck) {
            builder.mockCheck();
        }
        if (first.wrapThreadPoolTask || second.wrapThreadPoolTask) {
            builder.wrapThreadPoolTask();
        }
        return builder.build();
    }

    /**
     * Whether an {@code Object[]} of arguments must be materialised at method entry.
     */
    public boolean isCaptureArguments() {
        return captureArguments;
    }

    /**
     * Whether the returned array must be written back into the method's local variables.
     */
    public boolean isTransformArguments() {
        return transformArguments;
    }

    /**
     * Whether the return value must be boxed and handed to the sink.
     */
    public boolean isCaptureResult() {
        return captureResult;
    }

    /**
     * Whether the return value may be replaced by the sink.
     */
    public boolean isTransformResult() {
        return transformResult;
    }

    /**
     * Whether the entry hook must first consult the stub registry and short-circuit the body.
     */
    public boolean isMockCheck() {
        return mockCheck;
    }

    /**
     * Whether the entry hook must wrap the first {@code Runnable} argument so the trace context
     * survives the thread-pool hand-off.
     */
    public boolean isWrapThreadPoolTask() {
        return wrapThreadPoolTask;
    }

    /**
     * Whether the entry hook needs to be emitted at all beyond the baseline span.
     */
    public boolean isBaselineOnly() {
        return !captureArguments && !captureResult && !mockCheck && !wrapThreadPoolTask;
    }

    @Override
    public String toString() {
        return "JQuickHookPlan{args=" + captureArguments
                + ", transformArgs=" + transformArguments
                + ", result=" + captureResult
                + ", transformResult=" + transformResult
                + ", mock=" + mockCheck
                + ", threadPool=" + wrapThreadPoolTask + '}';
    }

    /**
     * Builder.
     */
    public static final class Builder {

        private boolean captureArguments;

        private boolean transformArguments;

        private boolean captureResult;

        private boolean transformResult;

        private boolean mockCheck;

        private boolean wrapThreadPoolTask;

        public Builder captureArguments() {
            this.captureArguments = true;
            return this;
        }

        public Builder transformArguments() {
            this.transformArguments = true;
            return this;
        }

        public Builder captureResult() {
            this.captureResult = true;
            return this;
        }

        public Builder transformResult() {
            this.transformResult = true;
            return this;
        }

        public Builder mockCheck() {
            this.mockCheck = true;
            return this;
        }

        public Builder wrapThreadPoolTask() {
            this.wrapThreadPoolTask = true;
            return this;
        }

        public JQuickHookPlan build() {
            return new JQuickHookPlan(this);
        }
    }
}
