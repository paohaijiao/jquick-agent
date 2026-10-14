package io.github.paohaijiao.jquick.agent.support;

import java.util.Collection;

/**
 * Class-name filtering shared by the transformer, the matchers and the plugins.
 *
 * <p>Two rules are absolute and not configurable:
 * <ol>
 *   <li>The JVM core ({@code java.*}, {@code javax.*}, {@code jdk.*}, {@code sun.*}) is never
 *       instrumented by default. Most of those classes cannot be retransformed and enhancing them
 *       risks corrupting the runtime.</li>
 *   <li>The agent's own infrastructure ({@code org.objectweb.asm.*}, {@code com.jquick.asm.*} and
 *       the agent package itself) is never instrumented: enhancing the byte-code editor while it is
 *       editing byte-code is a guaranteed self-destruction.</li>
 * </ol>
 *
 * <p>The JDK rule can be relaxed for a small, explicit allow list (for example
 * {@code java.util.concurrent.ThreadPoolExecutor}, which <em>is</em> retransformable on HotSpot and
 * is the standard way to propagate a trace context into a thread pool). Relaxing it is always an
 * explicit operator decision, never a default.
 *
 * @author Martin
 */
public final class JQuickClassFilters {

    /**
     * Package prefixes that are always skipped.
     */
    private static final String[] INFRASTRUCTURE_PREFIXES = new String[]{
            "java.",
            "javax.",
            "jdk.",
            "sun.",
            "com.sun.",
            "org.objectweb.asm.",
            "com.jquick.asm.",
            "io.github.paohaijiao.jquick.agent."
    };

    private JQuickClassFilters() {
    }

    /**
     * Whether the class belongs to the JVM or to the agent infrastructure.
     */
    public static boolean isInfrastructure(String className) {
        if (JQuickStringUtil.isBlank(className)) {
            return true;
        }
        for (String prefix : INFRASTRUCTURE_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the class is a JDK / JDK-extension class, i.e. the family protected by
     * {@link #isInfrastructure(String)} but relaxable through the {@code allowJdk} setting.
     */
    public static boolean isJdkClass(String className) {
        if (JQuickStringUtil.isBlank(className)) {
            return false;
        }
        return className.startsWith("java.")
                || className.startsWith("javax.")
                || className.startsWith("jdk.")
                || className.startsWith("sun.")
                || className.startsWith("com.sun.");
    }

    /**
     * Whether the class name starts with any of the supplied prefixes.
     */
    public static boolean matchesAnyPrefix(String className, Collection<String> prefixes) {
        if (className == null || prefixes == null || prefixes.isEmpty()) {
            return false;
        }
        for (String prefix : prefixes) {
            if (JQuickStringUtil.isNotBlank(prefix) && className.startsWith(prefix.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the class name is matched by any of the supplied patterns. A pattern may be an exact
     * name, a package prefix (auto completed with {@code .*}) or a glob containing {@code *}/{@code ?}.
     */
    public static boolean matchesAnyPattern(String className, Collection<String> patterns) {
        if (className == null || patterns == null || patterns.isEmpty()) {
            return false;
        }
        for (String raw : patterns) {
            if (JQuickStringUtil.isBlank(raw)) {
                continue;
            }
            String pattern = raw.trim();
            if (pattern.indexOf('*') < 0 && pattern.indexOf('?') < 0) {
                if (className.equals(pattern) || className.startsWith(pattern + ".")) {
                    return true;
                }
                continue;
            }
            if (JQuickStringUtil.wildcardMatch(pattern, className)) {
                return true;
            }
        }
        return false;
    }
}
