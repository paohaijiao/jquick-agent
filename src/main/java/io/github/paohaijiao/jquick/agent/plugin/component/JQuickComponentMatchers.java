package io.github.paohaijiao.jquick.agent.plugin.component;

import io.github.paohaijiao.jquick.agent.core.JQuickClassMatcher;
import io.github.paohaijiao.jquick.agent.core.JQuickConfig;

import java.util.List;

/**
 * Small shared helper for building the hierarchy based matchers used by the integration plugins.
 *
 * <p><b>Important limitation.</b> The matcher sees the <em>direct</em> superclass and the
 * <em>directly declared</em> interfaces of a class, because that is all the class file records
 * before the class is loaded. A deeply nested hierarchy must therefore list the intermediate type as
 * well: a Spring {@code DispatcherServlet} has {@code FrameworkServlet} as its direct superclass, so
 * both names appear in the default target list. This mirrors what production agents do — SkyWalking
 * ships exactly such enumerated lists — and it is stated in the README rather than hidden.
 *
 * @author Martin
 */
public final class JQuickComponentMatchers {

    private JQuickComponentMatchers() {
    }

    /**
     * Builds a matcher from {@code <plugin>.includes}, falling back to the supplied defaults when the
     * operator configured nothing.
     *
     * @param config   resolved configuration
     * @param key      configuration key, e.g. {@code http.includes}
     * @param defaults hierarchy names used when the key is absent
     */
    public static JQuickClassMatcher superTypes(JQuickConfig config, String key,
                                                List<String> defaults) {
        List<String> types = config.getList(key);
        if (types.isEmpty()) {
            types = defaults;
        }
        return JQuickClassMatcher.builder().matchSuperTypes(types).build();
    }

    /**
     * Builds a matcher that resolves {@code <plugin>.includes} <em>at match time</em>.
     *
     * <p>This is the form the integration plugins use: it keeps them constructible with a public
     * no-argument constructor, which in turn is what makes them discoverable through
     * {@code META-INF/services} rather than hard-wired into the bootstrap.
     *
     * @param key      configuration key, e.g. {@code http.includes}
     * @param defaults hierarchy names used when the key is absent
     */
    public static JQuickClassMatcher superTypes(String key, List<String> defaults) {
        return JQuickClassMatcher.builder().matchSuperTypesFromConfig(key, defaults).build();
    }
}
