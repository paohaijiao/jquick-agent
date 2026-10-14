package io.github.paohaijiao.jquick.agent.core;

import io.github.paohaijiao.jquick.agent.metrics.JQuickMeterRegistry;
import io.github.paohaijiao.jquick.agent.support.JQuickClassFilters;
import io.github.paohaijiao.jquick.agent.support.JQuickLogger;
import io.github.paohaijiao.jquick.agent.topology.JQuickTopology;
import io.github.paohaijiao.jquick.agent.trace.JQuickTraceSampler;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The agent runtime: the single object every instrumented call talks to.
 *
 * <p>It plays two roles, deliberately kept in one class so there is exactly one runtime state to
 * reason about:
 * <ul>
 *   <li><b>registration surface</b> — plugins write their listeners, gates, transformers and stub
 *       resolvers here during {@link JQuickPlugin#initialize(JQuickEnvironment)};</li>
 *   <li><b>dispatch surface</b> — the injected byte-code reaches it through
 *       {@link io.github.paohaijiao.jquick.agent.runtime.JQuickSink} on every call.</li>
 * </ul>
 *
 * <h3>Why a global singleton</h3>
 * Injected byte-code is static: it can only call {@code INVOKESTATIC} on a class it can name at
 * instrumentation time. There is no way to hand it an instance. Every production JVM agent
 * (SkyWalking, Pinpoint, ByteBuddy's agent, Byteman) therefore exposes exactly one such anchor, and
 * this is ours.
 *
 * <h3>Lazy default</h3>
 * {@link #getOrCreate()} builds an empty environment when the agent is not installed. That makes the
 * whole runtime usable from a plain unit test, from the demos and from the hot-fix API without a
 * {@code -javaagent} flag — and it means the injected code never has to null-check.
 *
 * <h3>Failure isolation</h3>
 * Every dispatch loop swallows exceptions from individual extension points. A plugin that throws can
 * therefore lose its own events, but can never affect another plugin, and can never affect the host
 * application.
 *
 * @author Martin
 */
public final class JQuickEnvironment {

    private static volatile JQuickEnvironment instance;

    private final JQuickConfig config;

    private final JQuickMethodRegistry registry = new JQuickMethodRegistry();

    private final JQuickMeterRegistry meters = new JQuickMeterRegistry();

    private final JQuickTopology topology = new JQuickTopology();

    private final List<JQuickPlugin> plugins = new CopyOnWriteArrayList<JQuickPlugin>();

    private final List<JQuickClassEnhancer> enhancers = new CopyOnWriteArrayList<JQuickClassEnhancer>();

    private final List<JQuickMethodListener> listeners = new CopyOnWriteArrayList<JQuickMethodListener>();

    private final List<JQuickInvocationGate> gates = new CopyOnWriteArrayList<JQuickInvocationGate>();

    private final List<JQuickArgumentTransformer> argumentTransformers =
            new CopyOnWriteArrayList<JQuickArgumentTransformer>();

    private final List<JQuickResultTransformer> resultTransformers =
            new CopyOnWriteArrayList<JQuickResultTransformer>();

    private final List<JQuickStubResolver> stubResolvers =
            new CopyOnWriteArrayList<JQuickStubResolver>();
    private final List<String> jdkAllowList = new CopyOnWriteArrayList<String>();
    private volatile JQuickTraceSampler sampler = JQuickTraceSampler.ALWAYS;
    private volatile boolean enabled = true;
    private volatile boolean captureStack = false;
    private volatile long slowThresholdMillis = 200L;
    private volatile boolean printSpans = false;
    private volatile boolean topologyEnabled = true;
    private volatile boolean threadPoolWrapping = false;

    private JQuickEnvironment(JQuickConfig config) {
        this.config = config == null ? JQuickConfig.empty() : config;
        apply(config);
    }

    /**
     * The installed environment, or {@code null} when the agent is not installed.
     */
    public static JQuickEnvironment get() {
        return instance;
    }

    /**
     * The installed environment, creating an empty one on first use.
     */
    public static JQuickEnvironment getOrCreate() {
        JQuickEnvironment current = instance;
        if (current != null) {
            return current;
        }
        synchronized (JQuickEnvironment.class) {
            if (instance == null) {
                instance = new JQuickEnvironment(JQuickConfig.empty());
                JQuickLogger.debug("empty JQuickEnvironment created (agent not installed)");
            }
            return instance;
        }
    }

    public static boolean isInstalled() {
        return instance != null;
    }

    //lifecycle

    /**
     * Publishes an environment as the runtime singleton.
     */
    public static void install(JQuickEnvironment environment) {
        instance = environment;
    }

    /**
     * Replaces the singleton with a fresh, empty environment. Test support only.
     */
    public static void reset() {
        synchronized (JQuickEnvironment.class) {
            instance = null;
        }
    }

    public static JQuickEnvironment create(JQuickConfig config) {
        return new JQuickEnvironment(config);
    }

    /**
     * Adds JDK classes a plugin needs to be able to instrument.
     *
     * <p>The infrastructure filter protects the JDK by default because most of it cannot be
     * retransformed. A capability that genuinely needs one specific JDK class — the canonical example
     * being {@code java.util.concurrent.ThreadPoolExecutor}, which <em>is</em> retransformable on
     * HotSpot and is the standard place to propagate a trace context into a pool — declares it here.
     * The list is a union of what the operator configured and what the enabled plugins requested, and
     * it is printed in the bootstrap report so it is never a hidden decision.
     */
    public void allowJdk(Collection<String> classNames) {
        if (classNames == null) {
            return;
        }
        for (String name : classNames) {
            if (name != null && !name.trim().isEmpty() && !jdkAllowList.contains(name.trim())) {
                jdkAllowList.add(name.trim());
            }
        }
    }

    /**
     * Whether a JDK class may be instrumented.
     */
    public boolean isJdkAllowed(String className) {
        if (className == null) {
            return false;
        }
        if (!JQuickClassFilters.isJdkClass(className)) {
            return true;
        }
        if (JQuickClassFilters.matchesAnyPattern(className, jdkAllowList)) {
            return true;
        }
        return JQuickClassFilters.matchesAnyPattern(className,
                config.getList(JQuickConfig.ALLOW_JDK));
    }

    public List<String> getJdkAllowList() {
        return Collections.unmodifiableList(jdkAllowList);
    }

    /* ------------------------------------------------------------------ configuration */

    private void apply(JQuickConfig source) {
        if (source == null) {
            return;
        }
        JQuickLogger.setQuiet(source.getBoolean(JQuickConfig.QUIET, false));
        JQuickLogger.setDebug(source.getBoolean(JQuickConfig.DEBUG, false));
        sampler = JQuickTraceSampler.probabilistic(
                source.getDouble(JQuickConfig.TRACE_SAMPLER_RATE, 1d));
        captureStack = source.getBoolean(JQuickConfig.TRACE_ERROR_STACK, false);
        slowThresholdMillis = source.getLong(JQuickConfig.TRACE_SLOW_MS, 200L);
        printSpans = source.getBoolean(JQuickConfig.TRACE_PRINT, false);
        topologyEnabled = source.getBoolean(JQuickConfig.TOPOLOGY_ENABLED, true);
    }

    public JQuickConfig getConfig() {
        return config;
    }

    public JQuickMeterRegistry getMeters() {
        return meters;
    }

    public JQuickTopology getTopology() {
        return topology;
    }

    public JQuickMethodRegistry getRegistry() {
        return registry;
    }

    public JQuickTraceSampler getSampler() {
        return sampler;
    }

    public void setSampler(JQuickTraceSampler sampler) {
        this.sampler = sampler == null ? JQuickTraceSampler.ALWAYS : sampler;
    }

    public boolean isCaptureStack() {
        return captureStack;
    }

    public void setCaptureStack(boolean value) {
        this.captureStack = value;
    }

    public long getSlowThresholdMillis() {
        return slowThresholdMillis;
    }

    public boolean isPrintSpans() {
        return printSpans;
    }

    public void setPrintSpans(boolean value) {
        this.printSpans = value;
    }

    /**
     * Whether outgoing ({@code EXIT}) spans are folded into the call graph.
     */
    public boolean isTopologyEnabled() {
        return topologyEnabled;
    }

    public void setTopologyEnabled(boolean value) {
        this.topologyEnabled = value;
    }

    /**
     * Global switch; when {@code false} every injected hook returns immediately.
     */
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        this.enabled = value;
    }

    public boolean isThreadPoolWrapping() {
        return threadPoolWrapping;
    }

    /**
     * Requests that the emitter wrap the first {@code Runnable} argument of every instrumented
     * method. Set by the thread plugin; it is a global byte-code decision, not a per-listener flag,
     * because the wrapper is applied to the submission path rather than to an event.
     */
    public void setThreadPoolWrapping(boolean value) {
        this.threadPoolWrapping = value;
    }

    /* ------------------------------------------------------------------ registration */

    public void addPlugin(JQuickPlugin plugin) {
        if (plugin != null) {
            plugins.add(plugin);
        }
    }

    public List<JQuickPlugin> getPlugins() {
        return Collections.unmodifiableList(plugins);
    }

    public void addEnhancer(JQuickClassEnhancer enhancer) {
        if (enhancer != null) {
            enhancers.add(enhancer);
        }
    }

    public void addListener(JQuickMethodListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void addGate(JQuickInvocationGate gate) {
        if (gate != null) {
            gates.add(gate);
        }
    }

    public void addArgumentTransformer(JQuickArgumentTransformer transformer) {
        if (transformer != null) {
            argumentTransformers.add(transformer);
        }
    }

    public void addResultTransformer(JQuickResultTransformer transformer) {
        if (transformer != null) {
            resultTransformers.add(transformer);
        }
    }

    public void addStubResolver(JQuickStubResolver resolver) {
        if (resolver != null) {
            stubResolvers.add(resolver);
        }
    }

    public List<JQuickClassEnhancer> getEnhancers() {
        return Collections.unmodifiableList(enhancers);
    }

    public List<JQuickMethodListener> getListeners() {
        return Collections.unmodifiableList(listeners);
    }

    public boolean hasInterceptors() {
        return !listeners.isEmpty() || !gates.isEmpty()
                || !argumentTransformers.isEmpty() || !resultTransformers.isEmpty();
    }

    /* ------------------------------------------------------------------ plan resolution */

    /**
     * Computes the hook plan every enhancer shares.
     *
     * <p>Only the extension points that observe or intercept <em>every</em> instrumented call
     * contribute here: the listeners, the gates, the stub resolvers and the two global switches.
     * Argument and result transformers are deliberately excluded, because they are class scoped
     * capabilities — the enhancer that selects the classes declares its own
     * {@link JQuickHookPlan}, which is then merged with this one. A capability that transforms two
     * classes therefore does not make the other ten thousand allocate an argument array.
     */
    public JQuickHookPlan computePlan() {
        JQuickHookPlan.Builder builder = JQuickHookPlan.builder();
        for (JQuickMethodListener listener : listeners) {
            if (listener.transformArguments()) {
                builder.transformArguments();
            } else if (listener.captureArguments()) {
                builder.captureArguments();
            }
            if (listener.transformResult()) {
                builder.transformResult();
            } else if (listener.captureResult()) {
                builder.captureResult();
            }
        }
        for (JQuickInvocationGate gate : gates) {
            if (gate.requiresArguments()) {
                builder.captureArguments();
            }
        }
        if (!stubResolvers.isEmpty()) {
            builder.mockCheck();
        }
        if (threadPoolWrapping) {
            builder.wrapThreadPoolTask();
        }
        // Note what is deliberately absent: {@code captureStack} is not part of the plan. A stack trace
        // needs no emitted byte-code — the JVM filled it in when the exception was thrown — so it is a
        // rendering decision taken by a listener (see JQuickEnvironment#isCaptureStack) rather than an
        // instrumentation cost. Advertising it here would imply an emitter that does something with it.
        return builder.build();
    }

    /* ------------------------------------------------------------------ dispatch */

    public void dispatch(JQuickMethodEvent event) {
        if (listeners.isEmpty()) {
            return;
        }
        for (JQuickMethodListener listener : listeners) {
            try {
                listener.onMethodEvent(event);
            } catch (Throwable ex) {
                JQuickLogger.suppressed("listener '" + listener.name() + "' failed", ex);
            }
        }
    }

    /**
     * Runs every gate; the first rejection propagates and aborts the instrumented call.
     */
    public void checkGates(JQuickInvocation invocation) {
        if (gates.isEmpty()) {
            return;
        }
        JQuickRejectedException rejection = null;
        for (JQuickInvocationGate gate : gates) {
            try {
                gate.inspect(invocation);
            } catch (JQuickRejectedException ex) {
                rejection = ex;
                break;
            } catch (Throwable ex) {
                JQuickLogger.suppressed("gate '" + gate.name() + "' failed", ex);
            }
        }
        if (rejection != null) {
            throw rejection;
        }
    }

    /**
     * Runs every argument transformer in order, threading the array through them.
     */
    public Object[] transformArguments(JQuickInvocation invocation, Object[] arguments) {
        Object[] current = arguments;
        for (JQuickArgumentTransformer transformer : argumentTransformers) {
            try {
                Object[] candidate = transformer.transform(invocation, current);
                if (candidate != null && candidate.length == current.length) {
                    current = candidate;
                } else {
                    JQuickLogger.suppressed("argument transformer '" + transformer.name()
                            + "' returned an incompatible array; original kept", null);
                }
            } catch (Throwable ex) {
                JQuickLogger.suppressed("argument transformer '" + transformer.name() + "' failed", ex);
            }
        }
        return current;
    }

    /**
     * Runs every result transformer in order, threading the value through them.
     */
    public Object transformResult(JQuickInvocation invocation, Object result, boolean success) {
        Object current = result;
        for (JQuickResultTransformer transformer : resultTransformers) {
            if (!success && !transformer.handlesErrors()) {
                continue;
            }
            try {
                current = transformer.transform(invocation, current);
            } catch (Throwable ex) {
                JQuickLogger.suppressed("result transformer '" + transformer.name() + "' failed", ex);
            }
        }
        return current;
    }

    /**
     * Resolves a stubbed value, or {@code null} when the method is not stubbed.
     */
    public Object resolveStub(String signature) {
        if (stubResolvers.isEmpty()) {
            return null;
        }
        for (JQuickStubResolver resolver : stubResolvers) {
            try {
                Object value = resolver.resolve(signature);
                if (value != null) {
                    return value;
                }
            } catch (Throwable ex) {
                JQuickLogger.suppressed("stub resolver '" + resolver.name() + "' failed", ex);
            }
        }
        return null;
    }

    /* ------------------------------------------------------------------ reporting */

    /**
     * One-line inventory of what is currently active, printed by the bootstrap.
     */
    public String describe() {
        String sb = "plugins=" + plugins.size() +
                ", enhancers=" + enhancers.size() +
                ", listeners=" + listeners.size() +
                ", gates=" + gates.size() +
                ", argTransformers=" + argumentTransformers.size() +
                ", resultTransformers=" + resultTransformers.size() +
                ", stubs=" + stubResolvers.size() +
                ", meters=" + meters.size() +
                ", methods=" + registry.size();
        return sb;
    }

    /**
     * Full inventory, one line per plugin.
     */
    public String report() {
        StringBuilder sb = new StringBuilder();
        for (JQuickPlugin plugin : plugins) {
            sb.append("  - ").append(plugin.category()).append('/').append(plugin.name())
                    .append(": ").append(plugin.description())
                    .append(" [enhancers=").append(plugin.enhancers().size()).append("]\n");
        }
        return sb.toString();
    }
}
