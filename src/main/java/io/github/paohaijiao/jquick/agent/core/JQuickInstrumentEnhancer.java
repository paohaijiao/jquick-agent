package io.github.paohaijiao.jquick.agent.core;

import com.jquick.asm.enhance.JQuickMethodAdvice;
import com.jquick.asm.enhance.JQuickMethodContext;
import com.jquick.asm.enhance.JQuickMethodEnhancer;
import io.github.paohaijiao.jquick.agent.support.JQuickBytecodeEmitter;
import io.github.paohaijiao.jquick.agent.support.JQuickDescriptorUtil;
import io.github.paohaijiao.jquick.agent.support.JQuickLogger;
import io.github.paohaijiao.jquick.agent.trace.JQuickSpanType;
import org.objectweb.asm.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The one and only byte-code emitter of the framework.
 *
 * <p>Every capability that instruments methods — the tracer, the JDBC plugin, RASP, the governance
 * plugin, coverage, mocking — uses this class and merely supplies a different
 * {@link JQuickClassMatcher}, {@link JQuickMethodMatcher} and component label. Having a single
 * emitter (rather than one per capability) is what keeps the injected sequences identical and
 * auditable, and it means a correctness fix here fixes every capability at once.
 *
 * <h3>Zero-intrusion guarantee</h3>
 * The emitter adds <b>instructions only</b>. It never registers a try/catch entry, never allocates a
 * local variable that the original code might be using, and never rewrites an existing instruction.
 * Concretely:
 * <ul>
 *   <li>the developer's own {@code try/catch} blocks keep working, because the emitter's exception
 *       handling is expressed as a {@code DUP}+call before the method's own {@code ATHROW} rather
 *       than as a competing handler — the JVM resolves an exception to the <em>first</em> matching
 *       table entry, and a handler injected at {@code visitCode} would shadow the application's;</li>
 *   <li>argument write-back reuses the operand stack ({@code DUP}) instead of a scratch local, so no
 *       existing local variable is ever clobbered;</li>
 *   <li>the return-value transformation is branch-free: box, swap, call, unbox.</li>
 * </ul>
 *
 * <h3>Cost control</h3>
 * What gets injected is decided by {@link JQuickHookPlan}, which is resolved from the listeners that
 * are actually registered. A class instrumented when only span observation is on carries one
 * {@code LDC} plus one {@code INVOKESTATIC} per hook and allocates nothing.
 *
 * @author Martin
 */
public final class JQuickInstrumentEnhancer implements JQuickClassEnhancer {

    /**
     * Internal name of the runtime sink.
     */
    private static final String SINK = "io/github/paohaijiao/jquick/agent/runtime/JQuickSink";

    private static final String ENTER_DESCRIPTOR = "(Ljava/lang/String;)V";

    private static final String ENTER_ARGS_DESCRIPTOR = "(Ljava/lang/String;[Ljava/lang/Object;)V";

    private static final String ENTER_TRANSFORM_DESCRIPTOR =
            "(Ljava/lang/String;[Ljava/lang/Object;)[Ljava/lang/Object;";

    private static final String EXIT_DESCRIPTOR = "(Ljava/lang/String;)V";

    private static final String EXIT_RESULT_DESCRIPTOR =
            "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;";

    private static final String ERROR_DESCRIPTOR = "(Ljava/lang/Throwable;Ljava/lang/String;)V";

    private static final String MOCK_DESCRIPTOR = "(Ljava/lang/String;)Ljava/lang/Object;";

    private static final String WRAP_RUNNABLE_DESCRIPTOR =
            "(Ljava/lang/Runnable;)Ljava/lang/Runnable;";

    private static final String RUNNABLE_DESCRIPTOR = "Ljava/lang/Runnable;";

    private final String name;

    private final JQuickClassMatcher classMatcher;

    private final JQuickMethodMatcher methodMatcher;

    private final JQuickSpanType spanType;

    private final String component;

    private final JQuickEnvironment environment;

    /**
     * Optional plan this enhancer requires on top of the environment-wide one, used by class scoped
     * capabilities such as transparent decryption that must not tax the whole application.
     */
    private final JQuickHookPlan planRequirement;

    public JQuickInstrumentEnhancer(String name, JQuickClassMatcher classMatcher,
                                    JQuickMethodMatcher methodMatcher, JQuickSpanType spanType,
                                    String component, JQuickEnvironment environment) {
        this(name, classMatcher, methodMatcher, spanType, component, environment, null);
    }

    public JQuickInstrumentEnhancer(String name, JQuickClassMatcher classMatcher,
                                    JQuickMethodMatcher methodMatcher, JQuickSpanType spanType,
                                    String component, JQuickEnvironment environment,
                                    JQuickHookPlan planRequirement) {
        this.name = name;
        this.classMatcher = classMatcher == null ? JQuickClassMatcher.NONE : classMatcher;
        this.methodMatcher = methodMatcher == null ? JQuickMethodMatcher.NONE : methodMatcher;
        this.spanType = spanType == null ? JQuickSpanType.LOCAL : spanType;
        this.component = component;
        this.environment = environment;
        this.planRequirement = planRequirement;
    }

    /**
     * Collects the {@code name+descriptor} of every method that already calls the runtime sink.
     *
     * <p>This is the marker that makes instrumentation idempotent. It is read from the instruction
     * stream rather than from a table of "methods we instrumented" because a table keyed by signature
     * would be wrong as soon as two class loaders define the same class name, and because the bytes are
     * the only artefact that cannot drift from reality.
     */
    private static Set<String> existingSinkCallers(byte[] classBytes) {
        final Set<String> instrumented = new HashSet<String>();
        try {
            ClassReader reader = new ClassReader(classBytes);
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                private String currentMethod;

                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    currentMethod = name + descriptor;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name,
                                                    String descriptor, boolean isInterface) {
                            if (SINK.equals(owner)) {
                                instrumented.add(currentMethod);
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (Throwable ex) {
            // Unreadable bytes cannot be enhanced safely; treating them as "already instrumented"
            // would silently disable the agent, so they are reported instead.
            JQuickLogger.suppressed("could not scan for existing instrumentation", ex);
        }
        return instrumented;
    }

    /**
     * Emits the entry sequence.
     *
     * <h3>The order is load bearing</h3>
     * <ol>
     *   <li><b>stub check</b> — first, because a stubbed method must never execute its body, and the
     *       check is the only thing that can return before the body does;</li>
     *   <li><b>entry hook</b> — this is what establishes the trace context (trace id, span id, parent)
     *       for the rest of the call;</li>
     *   <li><b>thread-pool wrap</b> — last, and this is the subtle one. The wrapper captures the
     *       caller's trace context as it is <em>at this moment</em>; emitting it before the entry hook
     *       would capture an empty context, so the submitted task would inherit nothing and the
     *       feature would silently do nothing at all. Running it last also means it wraps the
     *       arguments <em>after</em> any transformation has been written back, so it sees the values
     *       the body is about to use.</li>
     * </ol>
     */
    private static void emitEnter(JQuickMethodContext ctx, String ownerName, JQuickHookPlan plan) {
        MethodVisitor mv = ctx.methodVisitor();
        String signature = signatureOf(ctx, ownerName);
        if (plan.isMockCheck()) {
            emitMockCheck(mv, signature, ctx.descriptor());
        }
        if (plan.isTransformArguments()) {
            mv.visitLdcInsn(signature);
            emitArgumentArray(mv, ctx.descriptor(), ctx.isStatic());
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, SINK, "enterTransformingArgs",
                    ENTER_TRANSFORM_DESCRIPTOR, false);
            emitArgumentWriteBack(mv, ctx.descriptor(), ctx.isStatic());
        } else if (plan.isCaptureArguments()) {
            mv.visitLdcInsn(signature);
            emitArgumentArray(mv, ctx.descriptor(), ctx.isStatic());
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, SINK, "enterWithArgs",
                    ENTER_ARGS_DESCRIPTOR, false);
        } else {
            mv.visitLdcInsn(signature);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, SINK, "enter", ENTER_DESCRIPTOR, false);
        }
        if (plan.isWrapThreadPoolTask()) {
            emitWrapThreadPoolTask(mv, ctx.descriptor(), ctx.isStatic());
        }
    }

    /**
     * Emits the stub short-circuit:
     *
     * <pre>
     *   LDC sig; INVOKESTATIC mockCheck(String)Object
     *   DUP; IFNULL continue
     *   &lt;unbox&gt;; xRETURN          // stubbed: never execute the body
     *   continue: POP              // not stubbed: run the body
     * </pre>
     * <p>
     * Both paths leave a consistent operand stack at the merge point, and the frame is recomputed by
     * jquick-asm's {@code ClassWriter}, so the injected branch is verifier-safe.
     */
    private static void emitMockCheck(MethodVisitor mv, String signature, String descriptor) {
        String returnType = JQuickDescriptorUtil.returnDescriptor(descriptor);
        mv.visitLdcInsn(signature);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, SINK, "mockCheck", MOCK_DESCRIPTOR, false);
        mv.visitInsn(Opcodes.DUP);
        Label continueLabel = new Label();
        mv.visitJumpInsn(Opcodes.IFNULL, continueLabel);
        JQuickBytecodeEmitter.unbox(mv, returnType);
        JQuickBytecodeEmitter.emitReturn(mv, returnType);
        mv.visitLabel(continueLabel);
        mv.visitInsn(Opcodes.POP);
    }

    /**
     * Emits the thread-pool context propagation: the first {@code Runnable} parameter is replaced by
     * a context-aware wrapper before the body runs.
     *
     * <p>It reads the parameter from its local slot, so it must be emitted after any argument
     * transformation has been written back — and after the entry hook, because the wrapper's whole
     * purpose is to carry the trace context the entry hook just established.
     */
    private static void emitWrapThreadPoolTask(MethodVisitor mv, String descriptor, boolean isStatic) {
        List<String> parameters = JQuickDescriptorUtil.parameterDescriptors(descriptor);
        int index = -1;
        int slot = isStatic ? 0 : 1;
        for (int i = 0; i < parameters.size(); i++) {
            if (RUNNABLE_DESCRIPTOR.equals(parameters.get(i))) {
                index = slot;
                break;
            }
            slot += JQuickDescriptorUtil.slotSize(parameters.get(i));
        }
        if (index < 0) {
            return;
        }
        mv.visitVarInsn(Opcodes.ALOAD, index);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, SINK, "wrapRunnable",
                WRAP_RUNNABLE_DESCRIPTOR, false);
        mv.visitVarInsn(Opcodes.ASTORE, index);
    }

    /**
     * Materialises the arguments as an {@code Object[]}, boxing primitives on the way.
     */
    private static void emitArgumentArray(MethodVisitor mv, String descriptor, boolean isStatic) {
        List<String> parameters = JQuickDescriptorUtil.parameterDescriptors(descriptor);
        JQuickBytecodeEmitter.pushInt(mv, parameters.size());
        mv.visitTypeInsn(Opcodes.ANEWARRAY, JQuickBytecodeEmitter.OBJECT_INTERNAL_NAME);
        int slot = isStatic ? 0 : 1;
        for (int i = 0; i < parameters.size(); i++) {
            String type = parameters.get(i);
            mv.visitInsn(Opcodes.DUP);
            JQuickBytecodeEmitter.pushInt(mv, i);
            JQuickBytecodeEmitter.load(mv, type, slot);
            JQuickBytecodeEmitter.box(mv, type);
            mv.visitInsn(Opcodes.AASTORE);
            slot += JQuickDescriptorUtil.slotSize(type);
        }
    }

    /**
     * Writes the possibly transformed array back into the parameter slots.
     *
     * <p>The array is kept on the operand stack and {@code DUP}ed for every element, so no scratch
     * local variable is needed — the only safe slot index would have been unknown anyway.
     */
    private static void emitArgumentWriteBack(MethodVisitor mv, String descriptor, boolean isStatic) {
        List<String> parameters = JQuickDescriptorUtil.parameterDescriptors(descriptor);
        int slot = isStatic ? 0 : 1;
        for (int i = 0; i < parameters.size(); i++) {
            String type = parameters.get(i);
            mv.visitInsn(Opcodes.DUP);
            JQuickBytecodeEmitter.pushInt(mv, i);
            mv.visitInsn(Opcodes.AALOAD);
            JQuickBytecodeEmitter.unbox(mv, type);
            JQuickBytecodeEmitter.store(mv, type, slot);
            slot += JQuickDescriptorUtil.slotSize(type);
        }
        mv.visitInsn(Opcodes.POP);
    }

    //entry hook

    private static void emitExit(JQuickMethodContext ctx, String ownerName, int opcode,
                                 JQuickHookPlan plan) {
        MethodVisitor mv = ctx.methodVisitor();
        String signature = signatureOf(ctx, ownerName);
        if (opcode == Opcodes.ATHROW) {
            // The throwable is on the stack top and must remain there for the real ATHROW; the
            // duplicate is what the hook consumes.
            mv.visitInsn(Opcodes.DUP);
            mv.visitLdcInsn(signature);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, SINK, "error", ERROR_DESCRIPTOR, false);
            return;
        }
        if (!plan.isCaptureResult()) {
            // The return value (if any) stays untouched below the pushed String.
            mv.visitLdcInsn(signature);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, SINK, "exit", EXIT_DESCRIPTOR, false);
            return;
        }
        String returnType = JQuickDescriptorUtil.returnDescriptor(ctx.descriptor());
        if (JQuickDescriptorUtil.isVoid(returnType)) {
            mv.visitLdcInsn(signature);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, SINK, "exitWithResult",
                    EXIT_RESULT_DESCRIPTOR, false);
            mv.visitInsn(Opcodes.POP);
            return;
        }
        // box [v] -> [Object]; push the signature -> [Object, String]; SWAP -> [String, Object]
        // (both operands are category-1 references, so SWAP is legal for every return type).
        JQuickBytecodeEmitter.box(mv, returnType);
        mv.visitLdcInsn(signature);
        mv.visitInsn(Opcodes.SWAP);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, SINK, "exitWithResult",
                EXIT_RESULT_DESCRIPTOR, false);
        JQuickBytecodeEmitter.unbox(mv, returnType);
    }

    private static String signatureOf(JQuickMethodContext ctx, String ownerName) {
        String owner = ctx.ownerInternalName() != null ? ctx.ownerInternalName() : ownerName;
        return JQuickDescriptorUtil.toSignature(owner, ctx.name(), ctx.descriptor());
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean supports(JQuickClassContext context) {
        JQuickClassMeta meta = context.getMeta();
        if (meta == null || !meta.hasModifiableBody()) {
            return false;
        }
        return classMatcher.matches(meta);
    }

    @Override
    public byte[] enhance(final JQuickClassContext context) {
        // Which methods already report to the sink. Computed from the bytes rather than from agent
        // bookkeeping, so it stays correct when the same class name is loaded by several class
        // loaders and when a transformer chain is replayed.
        final Set<String> alreadyInstrumented = existingSinkCallers(context.getClassBytes());
        final JQuickHookPlan plan = resolvePlan(context);
        final String ownerName = context.getClassName();
        final JQuickClassMeta classMeta = context.getMeta();
        return JQuickMethodEnhancer.from(context.getClassBytes())
                .match(new java.util.function.Predicate<JQuickMethodEnhancer.MethodMeta>() {
                    @Override
                    public boolean test(JQuickMethodEnhancer.MethodMeta meta) {
                        return accept(meta, classMeta, ownerName, alreadyInstrumented);
                    }
                })
                .advice(JQuickMethodAdvice.builder()
                        .onEnter(ctx -> emitEnter(ctx, ownerName, plan))
                        .onExit((ctx, opcode) -> emitExit(ctx, ownerName, opcode, plan))
                        .build())
                .apply();
    }

    //exit hook

    /**
     * Selection hook; also the place where a matched method's capability claim is registered.
     *
     * <p>Two responsibilities, in a fixed order, and both matter:
     * <ol>
     *   <li><b>Register the claim</b> — always, even when this enhancer will not emit. The registry
     *       merges claims ({@link JQuickMethodMeta}), so a method selected by both the tracer and an
     *       integration ends up with the union of their span type and component labels. This is what
     *       makes the emitted hook able to describe the method completely.</li>
     *   <li><b>Emit at most once</b> — a method whose byte-code already calls the sink is left alone.
     *       Without this, a class selected by two capabilities gets two complete hook sets, so every
     *       call is observed twice, counted twice, and — for a result transformer such as encryption —
     *       transformed twice, which is not idempotent and silently corrupts the value.</li>
     * </ol>
     */
    private boolean accept(JQuickMethodEnhancer.MethodMeta meta, JQuickClassMeta classMeta,
                           String ownerName, Set<String> alreadyInstrumented) {
        if (!methodMatcher.matches(meta.name, meta.descriptor, meta.access, classMeta)) {
            return false;
        }
        String signature = JQuickDescriptorUtil.toSignature(ownerName, meta.name, meta.descriptor);
        io.github.paohaijiao.jquick.agent.runtime.JQuickSink.registerMethod(
                signature, ownerName, meta.name, meta.descriptor, meta.access, spanType, component);
        return !alreadyInstrumented.contains(meta.name + meta.descriptor);
    }

    /**
     * The hook plan in force for this class.
     *
     * <p>It is the union of three things: the environment-wide plan (listeners, gates, stub resolvers),
     * this enhancer's own requirement, and <em>the requirements of every sibling enhancer whose
     * matcher also accepts this class</em>.
     *
     * <p>That third part is the part that is easy to miss. Enhancers are consulted in order and the
     * first one to accept a class emits the hooks; if it only knew its own requirement, a class
     * selected by both the tracer and a result-transforming capability would get the tracer's minimal
     * hooks and the transformation would never be applied. Asking the siblings makes the emitted
     * sequence a function of the whole set of capabilities interested in the class, independent of the
     * order they happen to be registered in.
     */
    private JQuickHookPlan resolvePlan(JQuickClassContext context) {
        JQuickHookPlan plan = JQuickHookPlan.merge(environment.computePlan(), planRequirement);
        JQuickClassMeta classMeta = context.getMeta();
        for (JQuickClassEnhancer candidate : environment.getEnhancers()) {
            if (candidate == this || !(candidate instanceof JQuickInstrumentEnhancer)) {
                continue;
            }
            JQuickInstrumentEnhancer sibling = (JQuickInstrumentEnhancer) candidate;
            if (sibling.planRequirement == null) {
                continue;
            }
            try {
                if (sibling.classMatcher.matches(classMeta)) {
                    plan = JQuickHookPlan.merge(plan, sibling.planRequirement);
                }
            } catch (Throwable ex) {
                JQuickLogger.suppressed("could not evaluate the plan of enhancer '"
                        + sibling.name, ex);
            }
        }
        return plan;
    }
}
