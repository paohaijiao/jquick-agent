package io.github.paohaijiao.jquick.agent.bootstrap;

import io.github.paohaijiao.jquick.agent.core.*;
import io.github.paohaijiao.jquick.agent.support.JQuickClassFilters;
import io.github.paohaijiao.jquick.agent.support.JQuickLogger;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The single {@link ClassFileTransformer} of the agent.
 *
 * <p>Exactly one transformer is registered, no matter how many capabilities are switched on. Every
 * capability contributes an {@link JQuickClassEnhancer} to the environment instead, and this class
 * walks that list. Registering one transformer per capability would work too — but it would mean N
 * passes over every class file, N copies of the filter logic, and a class that two transformers
 * disagree about being silently double-instrumented. Centralising the pass makes the ordering
 * explicit, the cost predictable and the filter logic testable.
 *
 * <h3>Screening order, cheapest first</h3>
 * <ol>
 *   <li>unnamed classes ({@code null} name) are rejected without touching the bytes;</li>
 *   <li>JVM core and the agent's own packages are rejected;</li>
 *   <li>JDK classes are rejected unless a plugin explicitly asked for that one class;</li>
 *   <li>{@code includes} / {@code excludes} are applied to the name only — still no parsing;</li>
 *   <li>only then is the class file parsed, by {@link JQuickClassMeta#of(byte[])}.</li>
 * </ol>
 * Steps 1–4 are pure string work, which matters: the transformer sees <em>every</em> class the
 * application loads, so anything expensive at the front of this method is paid thousands of times.
 *
 * <h3>Failure policy</h3>
 * Any {@link Throwable} — a corrupt class file, an enhancer bug, a class the JVM has already begun to
 * link — results in {@code null}, meaning "leave this class exactly as it was", plus one suppressed
 * log line. The transformer is the one place in the agent where a mistake would damage the host
 * application rather than merely lose telemetry, so it never lets an exception escape.
 *
 * @author Martin
 */
public final class JQuickTransformer implements ClassFileTransformer {

    private final JQuickEnvironment environment;

    private final JQuickConfig config;

    private final List<String> includes;

    private final List<String> excludes;

    private final AtomicLong seen = new AtomicLong();

    private final AtomicLong candidates = new AtomicLong();

    private final AtomicLong enhanced = new AtomicLong();

    private final AtomicLong failed = new AtomicLong();

    public JQuickTransformer(JQuickEnvironment environment) {
        this.environment = environment;
        this.config = environment.getConfig();
        this.includes = config.getList(JQuickConfig.INCLUDES);
        this.excludes = config.getList(JQuickConfig.EXCLUDES);
        // A silent agent is the worst kind of agent: if includes is empty nothing is ever
        // instrumented, so say so once at install time rather than leaving the operator staring at
        // an empty trace. "*" is the explicit opt-in for "every application class".
        if (includes.isEmpty()) {
            JQuickLogger.info("no 'includes' rule configured: no class will be instrumented "
                    + "(use includes=" + JQuickConfig.INCLUDE_ALL + " to instrument every "
                    + "application class)");
        }
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer)
            throws IllegalClassFormatException {
        try {
            return doTransform(loader, className, classBeingRedefined, classfileBuffer);
        } catch (Throwable ex) {
            failed.incrementAndGet();
            // Returning null means "unchanged"; the application keeps its original byte-code.
            JQuickLogger.suppressed("enhancement skipped for "
                    + (className == null ? "<unnamed>" : className.replace('/', '.')), ex);
            return null;
        }
    }

    private byte[] doTransform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                               byte[] classfileBuffer) {
        seen.incrementAndGet();
        if (className == null || classfileBuffer == null || classfileBuffer.length == 0) {
            return null;
        }
        String dottedName = className.replace('/', '.');
        if (!isCandidate(dottedName)) {
            return null;
        }
        boolean retransformation = classBeingRedefined != null;
        if (retransformation && !JQuickInstrumentation.canRetransform()) {
            // The manifest did not ask for Can-Retransform-Classes, so there is nothing to replay.
            return null;
        }
        candidates.incrementAndGet();
        JQuickClassMeta meta = JQuickClassMeta.of(classfileBuffer);
        if (meta == null) {
            return null;
        }
        JQuickClassContext context = new JQuickClassContext(
                dottedName, loader, classfileBuffer, meta, retransformation);
        boolean changed = false;
        for (JQuickClassEnhancer enhancer : environment.getEnhancers()) {
            try {
                if (!enhancer.supports(context)) {
                    continue;
                }
                byte[] result = enhancer.enhance(context);
                if (result != null && result.length > 0) {
                    // Thread the output into the next enhancer so two capabilities can coexist on
                    // the same class instead of the last one winning.
                    context = context.withBytes(result);
                    changed = true;
                }
            } catch (Throwable ex) {
                failed.incrementAndGet();
                JQuickLogger.suppressed("enhancer '" + enhancer.name() + "' failed for "
                        + dottedName, ex);
            }
        }
        if (!changed) {
            return null;
        }
        enhanced.incrementAndGet();
        JQuickLogger.debug("instrumented " + dottedName
                + (retransformation ? " (retransformation)" : ""));
        return context.getClassBytes();
    }

    /**
     * Whether a class name may be handed to the enhancers. Exposed so the bootstrap can retransform
     * exactly the already-loaded classes the transformer would have accepted.
     *
     * <p>This is intentionally the same predicate the transform pass uses; duplicating it is how
     * "why was my class instrumented on startup but not on attach" bugs are born.
     */
    public boolean isCandidate(String dottedName) {
        if (JQuickClassFilters.isInfrastructure(dottedName)) {
            return false;
        }
        if (JQuickClassFilters.isJdkClass(dottedName)
                && !environment.isJdkAllowed(dottedName)) {
            return false;
        }
        if (!includes.isEmpty()
                && !JQuickClassFilters.matchesAnyPattern(dottedName, includes)) {
            return false;
        }
        return !JQuickClassFilters.matchesAnyPattern(dottedName, excludes);
    }

    /**
     * One-line counters, printed in the bootstrap report and useful when a class "should have been"
     * instrumented: {@code candidates == 0} means the filter rejected it, while
     * {@code candidates > 0 && enhanced == 0} means every enhancer declined it.
     */
    public String stats() {
        return "seen=" + seen.get()
                + ", candidates=" + candidates.get()
                + ", enhanced=" + enhanced.get()
                + ", failed=" + failed.get();
    }

    public long getSeen() {
        return seen.get();
    }

    public long getCandidates() {
        return candidates.get();
    }

    public long getEnhanced() {
        return enhanced.get();
    }

    public long getFailed() {
        return failed.get();
    }
}
