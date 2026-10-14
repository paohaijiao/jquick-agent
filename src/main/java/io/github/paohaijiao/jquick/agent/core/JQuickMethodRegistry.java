package io.github.paohaijiao.jquick.agent.core;

import io.github.paohaijiao.jquick.agent.support.JQuickLogger;
import io.github.paohaijiao.jquick.agent.trace.JQuickSpanType;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridge between instrumentation time and runtime.
 *
 * <p>The injected byte-code carries one {@code String} constant — the readable signature — and
 * nothing else. Everything the sink needs to build a span (class, method, span type, component) is
 * looked up here once per call. This is the mechanism that keeps the injected code minimal while
 * still allowing rich, plugin-defined semantics.
 *
 * @author Martin
 */
public final class JQuickMethodRegistry {

    private final Map<String, JQuickMethodMeta> methods =
            new ConcurrentHashMap<String, JQuickMethodMeta>();

    /**
     * Registers a method; a later registration for the same signature is merged into the first one.
     */
    public void register(String signature, String className, String methodName, String descriptor,
                         int access, JQuickSpanType spanType, String component) {
        if (signature == null) {
            return;
        }
        JQuickMethodMeta candidate = new JQuickMethodMeta(signature, className, methodName,
                descriptor, access, spanType, component);
        JQuickMethodMeta existing = methods.putIfAbsent(signature, candidate);
        if (existing != null) {
            existing.merge(candidate);
        }
    }

    /**
     * Looks a method up; {@code null} when the signature was never registered, which can happen when
     * byte-code instrumented by a previous agent instance survives a hot redeploy.
     */
    public JQuickMethodMeta find(String signature) {
        if (signature == null) {
            return null;
        }
        return methods.get(signature);
    }

    /**
     * Resolves the metadata, falling back to a locally built instance when the signature is unknown.
     *
     * <p>The fallback parses the readable signature back into a class name and a method name, so an
     * unregistered call still produces a usable span instead of an {@code NPE}. The descriptor is
     * not recoverable from the readable form and is reported as {@code null}.
     */
    public JQuickMethodMeta resolve(String signature) {
        JQuickMethodMeta meta = find(signature);
        if (meta != null) {
            return meta;
        }
        if (signature == null) {
            return new JQuickMethodMeta("<unknown>", "<unknown>", "<unknown>", null, 0,
                    JQuickSpanType.LOCAL, null);
        }
        String className = signature;
        String methodName = "";
        int hash = signature.indexOf('#');
        if (hash >= 0) {
            className = signature.substring(0, hash);
            methodName = signature.substring(hash + 1);
            int paren = methodName.indexOf('(');
            if (paren >= 0) {
                methodName = methodName.substring(0, paren);
            }
        }
        JQuickMethodMeta built = new JQuickMethodMeta(signature, className, methodName, null, 0,
                JQuickSpanType.LOCAL, null);
        JQuickMethodMeta previous = methods.putIfAbsent(signature, built);
        if (previous != null) {
            return previous;
        }
        JQuickLogger.debug("unregistered signature resolved on the fly: " + signature);
        return built;
    }

    public int size() {
        return methods.size();
    }

    public Collection<JQuickMethodMeta> snapshot() {
        return Collections.unmodifiableCollection(methods.values());
    }

    public void clear() {
        methods.clear();
    }
}
