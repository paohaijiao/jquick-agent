package io.github.paohaijiao.jquick.agent.plugin.classload;

import io.github.paohaijiao.jquick.agent.core.JQuickRejectedException;
import io.github.paohaijiao.jquick.agent.support.JQuickClassFilters;
import io.github.paohaijiao.jquick.agent.support.JQuickLogger;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Sandbox rule set: class names that must not be loaded, or must not be executed against.
 *
 * <h3>An honest description of the enforcement</h3>
 * A {@code ClassFileTransformer} <b>cannot portably prevent a class from being loaded</b>. Returning
 * {@code null} means "leave it alone"; throwing from {@code transform} produces a class-loading
 * failure on HotSpot, but that behaviour is not part of the portable contract, and relying on it turns
 * a security control into a JVM-version-dependent coin flip.
 *
 * <p>What this guard therefore provides is a rule set with two reliable enforcement points:
 * <ul>
 *   <li><b>a gate</b> that refuses to execute an instrumented operation whose owner class is
 *       blacklisted, which is the practically useful behaviour — blocking the <em>call</em> rather
 *       than the <em>type</em>;</li>
 *   <li><b>a public assertion</b> ({@link #assertAllowed}) that application code, a custom class
 *       loader or the RASP layer can call at the point where a class name arrives from an untrusted
 *       source — a deserialisation allow list, a plugin loader, a scripting sandbox.</li>
 * </ul>
 *
 * <p>Combined with the agent's refusal to <em>instrument</em> a blacklisted class, that covers the
 * realistic use cases without pretending to be a JVM-level sandbox.
 *
 * @author Martin
 */
public final class JQuickClassLoadGuard {

    private static final List<String> BLACKLIST = new CopyOnWriteArrayList<String>();

    private JQuickClassLoadGuard() {
    }

    /**
     * Replaces the blacklist.
     */
    public static void configure(List<String> patterns) {
        BLACKLIST.clear();
        if (patterns != null) {
            for (String pattern : patterns) {
                if (pattern != null && !pattern.trim().isEmpty()) {
                    BLACKLIST.add(pattern.trim());
                }
            }
        }
    }

    public static List<String> blacklist() {
        return Collections.unmodifiableList(BLACKLIST);
    }

    public static boolean isBlacklisted(String className) {
        if (className == null || BLACKLIST.isEmpty()) {
            return false;
        }
        return JQuickClassFilters.matchesAnyPattern(className, BLACKLIST);
    }

    /**
     * Throws when the class is blacklisted.
     *
     * @param className binary class name
     * @throws JQuickRejectedException when the class is not allowed
     */
    public static void assertAllowed(String className) {
        if (isBlacklisted(className)) {
            JQuickLogger.warn("class '" + className + "' is in the sandbox blacklist");
            throw new JQuickRejectedException("sandbox.blacklist", className,
                    "class '" + className + "' is not allowed to be loaded or executed");
        }
    }
}
