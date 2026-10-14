package io.github.paohaijiao.jquick.agent.core;

import io.github.paohaijiao.jquick.agent.support.JQuickClassFilters;
import io.github.paohaijiao.jquick.agent.support.JQuickStringUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Declarative class-selection rule.
 *
 * <p>Three independent dimensions are supported and they are combined with OR:
 * <ol>
 *   <li><b>name</b> — an exact class name, a package prefix, or a glob containing {@code *}/{@code ?};</li>
 *   <li><b>hierarchy</b> — the direct superclass or one of the directly implemented interfaces. This
 *       is how framework integration is normally expressed: {@code matchSuperType("javax.servlet.http.HttpServlet")}
 *       instruments every servlet without naming a single class;</li>
 *   <li><b>annotation</b> — the class or any of its methods carries the annotation. This is the
 *       declarative route: {@code matchAnnotation("org.springframework.web.bind.annotation.RestController")}.</li>
 * </ol>
 *
 * <p>An empty matcher matches <b>nothing</b>. That asymmetry with "match everything" is intentional:
 * a plugin that is misconfigured must be inert, never accidentally global.
 *
 * @author Martin
 */
public final class JQuickClassMatcher {

    /**
     * Matches every class. Still subject to the infrastructure filter applied by the transformer.
     */
    public static final JQuickClassMatcher ALL = builder().matchAll().build();

    /**
     * Matches nothing.
     */
    public static final JQuickClassMatcher NONE = builder().build();

    private final boolean matchAll;

    private final List<String> namePatterns;

    private final List<String> superTypes;

    private final List<String> annotations;

    /**
     * Configuration key whose value may replace {@link #superTypes}, and the defaults used when the
     * key is absent. Both are {@code null} for an ordinary matcher.
     */
    private final String superTypesConfigKey;

    private final List<String> superTypesDefaults;

    private volatile List<String> resolvedSuperTypes;

    private volatile JQuickConfig resolvedConfig;

    private JQuickClassMatcher(Builder builder) {
        this.matchAll = builder.matchAll;
        this.namePatterns = Collections.unmodifiableList(new ArrayList<String>(builder.namePatterns));
        this.superTypes = Collections.unmodifiableList(new ArrayList<String>(builder.superTypes));
        this.annotations = Collections.unmodifiableList(new ArrayList<String>(builder.annotations));
        this.superTypesConfigKey = builder.superTypesConfigKey;
        this.superTypesDefaults = builder.superTypesDefaults == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(builder.superTypesDefaults));
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Convenience factory for the common "instrument this package" rule.
     */
    public static JQuickClassMatcher byName(String... patterns) {
        Builder builder = builder();
        for (String pattern : patterns) {
            builder.matchName(pattern);
        }
        return builder.build();
    }

    /**
     * Convenience factory for the common "instrument every implementation of this framework type"
     * rule.
     */
    public static JQuickClassMatcher bySuperType(String... superTypes) {
        Builder builder = builder();
        for (String superType : superTypes) {
            builder.matchSuperType(superType);
        }
        return builder.build();
    }

    public boolean matches(JQuickClassMeta meta) {
        if (meta == null || matchAll) {
            return matchAll;
        }
        if (JQuickClassFilters.matchesAnyPattern(meta.getClassName(), namePatterns)) {
            return true;
        }
        if (matchesSuperType(meta)) {
            return true;
        }
        return matchesAnnotation(meta);
    }

    private boolean matchesSuperType(JQuickClassMeta meta) {
        List<String> types = superTypes();
        if (types.isEmpty()) {
            return false;
        }
        String superName = meta.getSuperClassName();
        if (superName != null && types.contains(superName)) {
            return true;
        }
        for (String interfaceName : meta.getInterfaceNames()) {
            if (types.contains(interfaceName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The super-type list actually in force.
     *
     * <p>When the matcher was declared with a configuration key, resolution is deferred to the first
     * match and re-resolved only if the configuration object changed. Deferral is what lets a plugin
     * be instantiated by the standard {@code ServiceLoader} — i.e. with a public no-argument
     * constructor — instead of demanding the operator's configuration before the agent exists.
     */
    private List<String> superTypes() {
        if (superTypesConfigKey == null) {
            return superTypes;
        }
        JQuickConfig config = JQuickEnvironment.getOrCreate().getConfig();
        List<String> current = resolvedSuperTypes;
        if (current != null && resolvedConfig == config) {
            return current;
        }
        synchronized (this) {
            if (resolvedSuperTypes == null || resolvedConfig != config) {
                List<String> configured = config.getList(superTypesConfigKey);
                resolvedSuperTypes = configured.isEmpty() ? superTypesDefaults : configured;
                resolvedConfig = config;
            }
            return resolvedSuperTypes;
        }
    }

    private boolean matchesAnnotation(JQuickClassMeta meta) {
        if (annotations.isEmpty()) {
            return false;
        }
        for (String annotation : annotations) {
            if (meta.hasAnnotationAnywhere(annotation)) {
                return true;
            }
        }
        return false;
    }

    public boolean isEmpty() {
        return !matchAll && namePatterns.isEmpty() && superTypes.isEmpty()
                && superTypesConfigKey == null && annotations.isEmpty();
    }

    public List<String> getNamePatterns() {
        return namePatterns;
    }

    public List<String> getSuperTypes() {
        return superTypes();
    }

    public List<String> getAnnotations() {
        return annotations;
    }

    @Override
    public String toString() {
        if (matchAll) {
            return "JQuickClassMatcher{ALL}";
        }
        return "JQuickClassMatcher{names=" + namePatterns
                + ", superTypes=" + superTypes
                + (superTypesConfigKey == null ? "" : ", superTypesFrom=" + superTypesConfigKey)
                + ", annotations=" + annotations + '}';
    }

    /**
     * Builder; every list is optional and OR-combined.
     */
    public static final class Builder {

        private final List<String> namePatterns = new ArrayList<String>();
        private final List<String> superTypes = new ArrayList<String>();
        private final List<String> annotations = new ArrayList<String>();
        private boolean matchAll;
        private String superTypesConfigKey;

        private List<String> superTypesDefaults;

        public Builder matchAll() {
            this.matchAll = true;
            return this;
        }

        public Builder matchName(String pattern) {
            if (JQuickStringUtil.isNotBlank(pattern)) {
                namePatterns.add(pattern.trim());
            }
            return this;
        }

        public Builder matchNames(List<String> patterns) {
            if (patterns != null) {
                for (String pattern : patterns) {
                    matchName(pattern);
                }
            }
            return this;
        }

        public Builder matchSuperType(String type) {
            if (JQuickStringUtil.isNotBlank(type)) {
                superTypes.add(type.trim());
            }
            return this;
        }

        public Builder matchSuperTypes(List<String> types) {
            if (types != null) {
                for (String type : types) {
                    matchSuperType(type);
                }
            }
            return this;
        }

        /**
         * Declares the super-type list <em>by configuration key</em> instead of by value.
         *
         * <p>Resolution happens on the first match, against whatever configuration the installed
         * environment carries, falling back to {@code defaults} when the operator configured
         * nothing. This is what lets an integration plugin be a plain no-argument class.
         *
         * @param key      configuration key, e.g. {@code http.includes}
         * @param defaults hierarchy names used when the key is absent
         */
        public Builder matchSuperTypesFromConfig(String key, List<String> defaults) {
            if (JQuickStringUtil.isNotBlank(key)) {
                this.superTypesConfigKey = key.trim();
                this.superTypesDefaults = defaults;
            }
            return this;
        }

        public Builder matchAnnotation(String annotation) {
            if (JQuickStringUtil.isNotBlank(annotation)) {
                annotations.add(annotation.trim());
            }
            return this;
        }

        public JQuickClassMatcher build() {
            return new JQuickClassMatcher(this);
        }
    }
}
