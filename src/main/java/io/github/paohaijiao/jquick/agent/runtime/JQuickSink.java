package io.github.paohaijiao.jquick.agent.runtime;

import io.github.paohaijiao.jquick.agent.core.*;
import io.github.paohaijiao.jquick.agent.support.JQuickDescriptorUtil;
import io.github.paohaijiao.jquick.agent.support.JQuickIdGenerator;
import io.github.paohaijiao.jquick.agent.support.JQuickLogger;
import io.github.paohaijiao.jquick.agent.trace.JQuickSpan;
import io.github.paohaijiao.jquick.agent.trace.JQuickSpanType;
import io.github.paohaijiao.jquick.agent.trace.JQuickTraceContext;
import io.github.paohaijiao.jquick.agent.trace.JQuickTraceScope;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Runtime entry point of the injected byte-code.
 *
 * <h3>This is a frozen contract</h3>
 * Every method below is called by byte-code that was generated at <em>instrumentation</em> time and
 * may live in a class that was loaded minutes or weeks earlier. Their names, descriptors and
 * semantics must therefore never change: adding a method is safe, changing one is a breaking change
 * that silently corrupts already-instrumented classes. This is why the sink is a distinct, tiny
 * facade over a rich runtime rather than being the runtime itself.
 *
 * <h3>The injected sequences</h3>
 * <pre>
 *   entry      LDC "sig"                     INVOKESTATIC enter(String)V
 *   entry+args LDC "sig"  &lt;build Object[]&gt;  INVOKESTATIC enterWithArgs(String,Object[])V
 *   entry+xfrm LDC "sig"  &lt;build Object[]&gt;  INVOKESTATIC enterTransformingArgs(String,Object[])Object[]
 *                                           &lt;write back into locals&gt;
 *   exit       LDC "sig"                     INVOKESTATIC exit(String)V
 *   exit+value &lt;box value&gt;  LDC "sig" SWAP   INVOKESTATIC exitWithResult(String,Object)Object
 *                                           &lt;unbox&gt;
 *   throw      DUP  LDC "sig"                INVOKESTATIC error(Throwable,String)V
 *   stub check LDC "sig"                     INVOKESTATIC mockCheck(String)Object
 *                                           DUP  IFNULL &lt;continue&gt;  &lt;unbox&gt; xRETURN
 * </pre>
 *
 * <h3>Thread-local frame stack</h3>
 * The elapsed time of a call is measured by pushing a frame at entry and popping it at exit, rather
 * than by allocating a local variable in the instrumented method. The frame stack is also what makes
 * the call graph reconstructible: it is the only structure that knows who called whom.
 *
 * <p>Frames are matched by signature rather than blindly popped, so a method that is left through an
 * exception raised by a callee — where no return instruction of its own body runs — cannot corrupt
 * the bookkeeping. Stale frames are discarded together with the matched one, and the trace scope
 * unwinds to the same point.
 *
 * <h3>Failure isolation</h3>
 * Every public method is wrapped: a failure inside the agent can lose an event, but can never
 * propagate into the host application.
 *
 * @author Martin
 */
public final class JQuickSink {

    /**
     * Tag used for agent diagnostics.
     */
    public static final String TAG = "[jquick-agent]";

    /**
     * Hard cap on the per-thread frame stack. A deeper stack means an unbalanced pairing, and it must
     * not be allowed to grow without bound.
     */
    private static final int MAX_FRAMES = 512;

    private static final ThreadLocal<Deque<Frame>> FRAMES = new ThreadLocal<Deque<Frame>>() {
        @Override
        protected Deque<Frame> initialValue() {
            return new ArrayDeque<Frame>();
        }
    };

    private JQuickSink() {
    }


    /**
     * Entry hook without argument capture.
     *
     * @param signature readable method signature, a compile time constant baked into the class
     */
    public static void enter(String signature) {
        handleEnter(signature, null);
    }

    /**
     * Entry hook with argument capture. The array is observed but never written back.
     */
    public static void enterWithArgs(String signature, Object[] arguments) {
        handleEnter(signature, arguments);
    }

    /**
     * Entry hook with argument capture and transformation.
     *
     * @return the array the method body must run with; identical to the input when no transformer
     * changed anything
     */
    public static Object[] enterTransformingArgs(String signature, Object[] arguments) {
        return handleEnter(signature, arguments);
    }

    private static Object[] handleEnter(String signature, Object[] arguments) {
        JQuickEnvironment environment = environment();
        if (environment == null || !environment.isEnabled()) {
            return arguments;
        }
        JQuickInvocation invocation = null;
        try {
            invocation = beginInvocation(environment, signature, arguments);
            Object[] effective = arguments;
            if (arguments != null) {
                effective = environment.transformArguments(invocation, arguments);
            }
            // Note what is deliberately NOT done here: the invocation keeps the arguments as the
            // caller passed them, while the body runs with the transformed ones. The invocation
            // describes the process boundary, and that choice also keeps a decrypted payload out of
            // the telemetry — a trace log that shows the plaintext a crypto transformer just produced
            // would undo the very protection the transformer was installed for.
            environment.checkGates(invocation);
            environment.dispatch(new JQuickMethodEvent(
                    JQuickMethodEvent.JQuickPhase.ENTER, invocation, null, 0L, null));
            return effective;
        } catch (JQuickRejectedException rejection) {
            abandon(environment, invocation, rejection);
            throw rejection;
        } catch (Throwable ex) {
            JQuickLogger.suppressed("entry hook failed for " + signature, ex);
            return arguments;
        }
    }

    //exit hooks

    /**
     * Exit hook for a call whose return value is irrelevant to the agent.
     */
    public static void exit(String signature) {
        handleExit(signature, null, false);
    }

    /**
     * Exit hook for a call whose return value is observed, and possibly replaced.
     *
     * @return the value the caller must observe
     */
    public static Object exitWithResult(String signature, Object result) {
        return handleExit(signature, result, true);
    }

    private static Object handleExit(String signature, Object result, boolean hasResult) {
        JQuickEnvironment environment = environment();
        if (environment == null || !environment.isEnabled()) {
            return hasResult ? result : null;
        }
        try {
            Frame frame = popFrame(signature);
            if (frame == null) {
                return hasResult ? result : null;
            }
            JQuickInvocation invocation = frame.invocation;
            long elapsed = System.nanoTime() - invocation.getStartNanos();
            Object effective = result;
            if (hasResult) {
                effective = environment.transformResult(invocation, result, true);
                // Recorded after transformation: a listener is told what left the method, which is
                // the only value that explains the system's behaviour from the outside.
                invocation.setReturnValue(effective);
            }
            complete(environment, frame, invocation, elapsed, true, null);
            return hasResult ? effective : null;
        } catch (Throwable ex) {
            JQuickLogger.suppressed("exit hook failed for " + signature, ex);
            return hasResult ? result : null;
        }
    }

    //error hook

    /**
     * Hook emitted immediately before the {@code ATHROW} of an instrumented method.
     *
     * <p>The enhancer duplicates the throwable on the operand stack, so this method consumes the
     * duplicate and leaves the original in place for the real {@code ATHROW}: the application's
     * exception semantics are untouched.
     *
     * @param error     the throwable, never {@code null}
     * @param signature readable method signature
     */
    public static void error(Throwable error, String signature) {
        JQuickEnvironment environment = environment();
        if (environment == null || !environment.isEnabled()) {
            return;
        }
        try {
            Frame frame = popFrame(signature);
            if (frame == null) {
                return;
            }
            JQuickInvocation invocation = frame.invocation;
            long elapsed = System.nanoTime() - invocation.getStartNanos();
            complete(environment, frame, invocation, elapsed, false, error);
        } catch (Throwable ex) {
            JQuickLogger.suppressed("error hook failed for " + signature, ex);
        }
    }

    // stub hook

    /**
     * Hook emitted at the head of a method when the mocking capability is enabled.
     *
     * @return {@code null} when the method is not stubbed (run the real body), otherwise a non-null
     * value the emitter turns into an immediate return
     */
    public static Object mockCheck(String signature) {
        JQuickEnvironment environment = environment();
        if (environment == null || !environment.isEnabled()) {
            return null;
        }
        // Only the lookup is failure-isolated. Everything after it is deliberately outside the guard:
        // see the comment on the action branch below.
        Object stub;
        try {
            stub = environment.resolveStub(signature);
        } catch (Throwable ex) {
            JQuickLogger.suppressed("stub lookup failed for " + signature, ex);
            return null;
        }
        if (stub == null) {
            return null;
        }
        if (stub instanceof io.github.paohaijiao.jquick.agent.core.JQuickStubAction) {
            publishStubSpanQuietly(environment, signature);
            // The throwable must reach the caller — that is the entire purpose of a throwing stub, and
            // the way a fallback path or a circuit breaker is exercised. Wrapping this call in the
            // failure-isolating guard would swallow the exception and silently run the real body,
            // turning a stub that "works" into a test that asserts nothing.
            ((io.github.paohaijiao.jquick.agent.core.JQuickStubAction) stub).execute();
            // An action that returns normally asked for no substitution; run the real body.
            return null;
        }
        if (stub == JQuickStubResolver.VOID && !isVoidMethod(environment, signature)) {
            JQuickLogger.warn("a void stub was registered for the non-void method " + signature
                    + "; the stub is ignored");
            return null;
        }
        publishStubSpanQuietly(environment, signature);
        return stub;
    }

    /**
     * Publishes the span for a stubbed call, isolating its own failures.
     *
     * <p>Telemetry must never be able to break a stub: a broken listener would otherwise turn a
     * deliberately stubbed dependency into a hard failure of the code under test.
     */
    private static void publishStubSpanQuietly(JQuickEnvironment environment, String signature) {
        try {
            publishStubSpan(environment, signature);
        } catch (Throwable ex) {
            JQuickLogger.suppressed("stub span publication failed for " + signature, ex);
        }
    }

    private static boolean isVoidMethod(JQuickEnvironment environment, String signature) {
        JQuickMethodMeta meta = environment.getRegistry().find(signature);
        if (meta == null || meta.getDescriptor() == null) {
            return true;
        }
        return JQuickDescriptorUtil.isVoid(JQuickDescriptorUtil.returnDescriptor(meta.getDescriptor()));
    }

    private static void publishStubSpan(JQuickEnvironment environment, String signature) {
        JQuickMethodMeta meta = environment.getRegistry().resolve(signature);
        JQuickTraceScope scope = JQuickTraceContext.scope();
        if (!scope.isActive()) {
            scope.setTraceId(JQuickIdGenerator.newTraceId());
        }
        String spanId = JQuickIdGenerator.newSpanId();
        String parentSpanId = scope.beginSpan(spanId);
        JQuickInvocation invocation = new JQuickInvocation(
                meta, Thread.currentThread().getName(), System.nanoTime());
        invocation.setTraceId(scope.getTraceId());
        invocation.setSpanId(spanId);
        invocation.setParentSpanId(parentSpanId);
        invocation.setDepth(scope.depth());
        JQuickSpan span = JQuickSpan.builder()
                .traceId(invocation.getTraceId())
                .spanId(spanId)
                .parentSpanId(parentSpanId)
                .type(JQuickSpanType.LOCAL)
                .component(JQuickSpanComponent.MOCK)
                .operation(signature)
                .className(invocation.getClassName())
                .methodName(invocation.getMethodName())
                .threadName(invocation.getThreadName())
                .depth(invocation.getDepth())
                .startNanos(invocation.getStartNanos())
                .durationNanos(0L)
                .success(true)
                .tag("mock", "true")
                .build();
        scope.endSpan(spanId);
        environment.dispatch(new JQuickMethodEvent(
                JQuickMethodEvent.JQuickPhase.EXIT, invocation, span, 0L, null));
    }

    //thread-pool hook

    /**
     * Hook emitted at the head of a thread-pool submission method.
     *
     * <p>Replaces the submitted task with a wrapper that carries the caller's trace context into the
     * worker thread and clears it afterwards. This is the mechanism that fixes the classic
     * "trace id lost in the thread pool" problem: the propagation happens in the submission path, so
     * the task body does not have to know anything about it.
     *
     * @param task the task being submitted
     * @return the original task when the capability is off, otherwise a context-aware wrapper
     */
    public static Runnable wrapRunnable(Runnable task) {
        if (task == null) {
            return null;
        }
        JQuickEnvironment environment = environment();
        if (environment == null || !environment.isEnabled()) {
            return task;
        }
        try {
            if (task instanceof JQuickTraceAwareRunnable) {
                return task;
            }
            return new JQuickTraceAwareRunnable(task, JQuickTraceContext.capture());
        } catch (Throwable ex) {
            JQuickLogger.suppressed("thread pool hook failed", ex);
            return task;
        }
    }

    //helper API

    /**
     * Registers a method's metadata so the sink can build a full span for it.
     *
     * <p>Called by enhancers at instrumentation time; exposed publicly so user-written enhancers can
     * participate in the same mechanism.
     */
    public static void registerMethod(String signature, String className, String methodName,
                                      String descriptor, int access, JQuickSpanType spanType,
                                      String component) {
        try {
            JQuickEnvironment environment = environment();
            if (environment != null) {
                environment.getRegistry().register(signature, className, methodName, descriptor,
                        access, spanType, component);
            }
        } catch (Throwable ex) {
            JQuickLogger.suppressed("method registration failed for " + signature, ex);
        }
    }

    /**
     * Number of frames currently open on the calling thread. Diagnostic use only.
     */
    public static int frameDepth() {
        return FRAMES.get().size();
    }

    /**
     * Drops all thread state. Test support only.
     */
    public static void reset() {
        FRAMES.remove();
        JQuickTraceContext.clear();
    }

    //internals

    private static JQuickEnvironment environment() {
        JQuickEnvironment environment = JQuickEnvironment.get();
        return environment != null ? environment : JQuickEnvironment.getOrCreate();
    }

    private static JQuickInvocation beginInvocation(JQuickEnvironment environment, String signature,
                                                    Object[] arguments) {
        Deque<Frame> stack = FRAMES.get();
        String parentSignature = stack.isEmpty() ? null : stack.peek().invocation.getSignature();

        JQuickTraceScope scope = JQuickTraceContext.scope();
        if (!scope.isActive()) {
            scope.setTraceId(JQuickIdGenerator.newTraceId());
            scope.setSampled(environment.getSampler().shouldSample());
        }
        String spanId = JQuickIdGenerator.newSpanId();
        String parentSpanId = scope.beginSpan(spanId);

        JQuickMethodMeta meta = environment.getRegistry().resolve(signature);
        JQuickInvocation invocation = new JQuickInvocation(
                meta, Thread.currentThread().getName(), System.nanoTime());
        invocation.setTraceId(scope.getTraceId());
        invocation.setSpanId(spanId);
        invocation.setParentSpanId(parentSpanId);
        invocation.setDepth(scope.depth());
        invocation.setArguments(arguments);

        if (stack.size() >= MAX_FRAMES) {
            stack.pollLast();
        }
        stack.push(new Frame(invocation, parentSignature));
        return invocation;
    }

    /**
     * Closes an invocation: builds the span, folds the edge into the topology, unwinds the trace
     * scope and publishes the event.
     */
    private static void complete(JQuickEnvironment environment, Frame frame,
                                 JQuickInvocation invocation, long elapsedNanos,
                                 boolean success, Throwable error) {
        JQuickSpan span = buildSpan(invocation, elapsedNanos, success, error);
        recordTopology(environment, frame, invocation, elapsedNanos, success);
        JQuickTraceContext.scope().endSpan(invocation.getSpanId());
        if (FRAMES.get().isEmpty()) {
            FRAMES.remove();
            JQuickTraceContext.clear();
        }
        environment.dispatch(new JQuickMethodEvent(
                success ? JQuickMethodEvent.JQuickPhase.EXIT : JQuickMethodEvent.JQuickPhase.ERROR,
                invocation, span, elapsedNanos, error));
    }

    /**
     * Closes an invocation that was refused by a gate.
     */
    private static void abandon(JQuickEnvironment environment, JQuickInvocation invocation,
                                Throwable error) {
        if (invocation == null) {
            return;
        }
        try {
            popFrame(invocation.getSignature());
            long elapsed = System.nanoTime() - invocation.getStartNanos();
            complete(environment, new Frame(invocation, null), invocation, elapsed, false, error);
        } catch (Throwable ex) {
            JQuickLogger.suppressed("failed to close a rejected invocation", ex);
        }
    }

    private static void recordTopology(JQuickEnvironment environment, Frame frame,
                                       JQuickInvocation invocation, long elapsedNanos,
                                       boolean success) {
        if (!environment.isTopologyEnabled()) {
            return;
        }
        if (invocation.getSpanType() != JQuickSpanType.EXIT) {
            return;
        }
        environment.getTopology().record(frame.parentSignature, invocation.getSignature(),
                invocation.getComponent(), elapsedNanos, success);
    }

    private static JQuickSpan buildSpan(JQuickInvocation invocation, long elapsedNanos,
                                        boolean success, Throwable error) {
        return JQuickSpan.builder()
                .traceId(invocation.getTraceId())
                .spanId(invocation.getSpanId())
                .parentSpanId(invocation.getParentSpanId())
                .type(invocation.getSpanType())
                .component(invocation.getComponent())
                .operation(invocation.getSignature())
                .className(invocation.getClassName())
                .methodName(invocation.getMethodName())
                .threadName(invocation.getThreadName())
                .depth(invocation.getDepth())
                .startNanos(invocation.getStartNanos())
                .durationNanos(elapsedNanos)
                .success(success)
                .errorType(error == null ? null : error.getClass().getName())
                .errorMessage(error == null ? null : error.getMessage())
                .build();
    }

    /**
     * Pops the frame matching {@code signature}.
     *
     * <p>When the top of the stack belongs to another call, that call was left through an exception
     * raised by a callee. Such stale frames are discarded together with the matched one, which keeps
     * the arithmetic of the surrounding frames exact even in the presence of propagated exceptions.
     */
    private static Frame popFrame(String signature) {
        Deque<Frame> stack = FRAMES.get();
        if (stack.isEmpty()) {
            return null;
        }
        int stale = 0;
        Frame matched = null;
        for (Frame candidate : stack) {
            if (signature == null || signature.equals(candidate.invocation.getSignature())) {
                matched = candidate;
                break;
            }
            stale++;
        }
        if (matched == null) {
            // Never trust a broken pairing: fall back to the top frame so the stack keeps shrinking.
            return stack.poll();
        }
        for (int i = 0; i <= stale; i++) {
            stack.poll();
        }
        return matched;
    }

    /**
     * One open invocation plus the signature of its caller, used to build the topology.
     */
    private static final class Frame {

        private final JQuickInvocation invocation;

        private final String parentSignature;

        private Frame(JQuickInvocation invocation, String parentSignature) {
            this.invocation = invocation;
            this.parentSignature = parentSignature;
        }
    }

    /**
     * Component labels used by the sink itself.
     */
    public static final class JQuickSpanComponent {

        public static final String MOCK = "mock";

        private JQuickSpanComponent() {
        }
    }
}
