package io.github.paohaijiao.jquick.agent.plugin.mock;

import io.github.paohaijiao.jquick.agent.core.JQuickStubAction;
import io.github.paohaijiao.jquick.agent.core.JQuickStubResolver;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of stubbed methods, backing the byte-code level mocking capability.
 *
 * <p>The difference from a conventional mocking framework is <em>where</em> the stub is applied. A
 * proxy-based mock substitutes an object; a class-loader-based mock (Mockito inline, PowerMock)
 * rewrites byte-code at load time. This registry is consulted from the entry hook of the real
 * implementation: the original body is never entered, so the stub also covers calls made from
 * <em>inside</em> the class under test, from a static initialiser, and from code that obtained the
 * dependency through a static factory — the three places where a proxy-based mock silently fails.
 *
 * <p>Keyed by the readable signature, which is also what the agent prints in its diagnostics, so a
 * stub is declared with the same string an operator sees in the trace output.
 *
 * @author Martin
 */
public final class JQuickMockRegistry implements JQuickStubResolver {

    private final Map<String, Object> stubs = new ConcurrentHashMap<String, Object>();

    /**
     * Stubs a method so that it returns {@code value}.
     */
    public JQuickMockRegistry stubReturn(String signature, Object value) {
        if (signature != null) {
            stubs.put(signature, value);
        }
        return this;
    }

    /**
     * Stubs a {@code void} method so that it returns immediately.
     */
    public JQuickMockRegistry stubVoid(String signature) {
        if (signature != null) {
            stubs.put(signature, VOID);
        }
        return this;
    }

    /**
     * Stubs a method so that it fails with the supplied error. The most useful stub of all when the
     * goal is to test a fallback path or a circuit breaker.
     */
    public JQuickMockRegistry stubThrow(String signature, final Throwable error) {
        if (signature != null) {
            stubs.put(signature, new JQuickStubAction() {
                @Override
                public void execute() {
                    if (error instanceof RuntimeException) {
                        throw (RuntimeException) error;
                    }
                    if (error instanceof Error) {
                        throw (Error) error;
                    }
                    throw new IllegalStateException(error);
                }

                @Override
                public String toString() {
                    return "throw " + error;
                }
            });
        }
        return this;
    }

    /**
     * Registers an arbitrary action.
     */
    public JQuickMockRegistry stubAction(String signature, JQuickStubAction action) {
        if (signature != null && action != null) {
            stubs.put(signature, action);
        }
        return this;
    }

    /**
     * Removes a stub.
     */
    public JQuickMockRegistry unstub(String signature) {
        if (signature != null) {
            stubs.remove(signature);
        }
        return this;
    }

    public boolean isStubbed(String signature) {
        return stubs.containsKey(signature);
    }

    @Override
    public Object resolve(String signature) {
        return signature == null ? null : stubs.get(signature);
    }

    public Set<String> stubbedSignatures() {
        return Collections.unmodifiableSet(stubs.keySet());
    }

    public int size() {
        return stubs.size();
    }

    public void clear() {
        stubs.clear();
    }

    /**
     * Snapshot of the registry, for reporting.
     */
    public Map<String, String> describe() {
        Map<String, String> description = new LinkedHashMap<String, String>();
        for (Map.Entry<String, Object> entry : stubs.entrySet()) {
            description.put(entry.getKey(), String.valueOf(entry.getValue()));
        }
        return description;
    }

    @Override
    public String name() {
        return "mock-registry";
    }
}
