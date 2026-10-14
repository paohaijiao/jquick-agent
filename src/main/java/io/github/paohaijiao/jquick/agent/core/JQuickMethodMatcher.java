package io.github.paohaijiao.jquick.agent.core;

import io.github.paohaijiao.jquick.agent.support.JQuickStringUtil;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Declarative method-selection rule.
 *
 * <p>Complements {@link JQuickClassMatcher}: once a class is selected, this decides which of its
 * methods are worth instrumenting.
 *
 * <p>Two filters are on by default because instrumenting them is actively harmful:
 * <ul>
 *   <li><b>synthetic and bridge members</b> — compiler artefacts ({@code lambda$...}, accessors for
 *       nested classes, covariant bridges) multiply the event stream without adding information, and
 *       are frequently called from a frame where the receiver is {@code null};</li>
 *   <li><b>{@code toString} / {@code hashCode} / {@code equals}</b> — instrumenting them turns any
 *       logging statement into a recursion and any hash-map operation into a flood.</li>
 * </ul>
 * Constructors, static initialisers, native and abstract methods are excluded by jquick-asm's
 * {@code JQuickEnhanceGuard} unconditionally and are therefore not repeated here.
 *
 * @author Martin
 */
public final class JQuickMethodMatcher {

    public static final JQuickMethodMatcher ALL = builder().matchAll().build();
    public static final JQuickMethodMatcher NONE = builder().build();
    /**
     * Methods that are never instrumented by default.
     */
    private static final List<String> DEFAULT_EXCLUDED = Collections.unmodifiableList(
            Arrays.asList("toString", "hashCode", "equals", "clone", "finalize", "main"));
    private final boolean matchAll;

    private final List<String> names;

    private final List<String> namePrefixes;

    private final List<String> excludedNames;

    private final List<String> excludedPrefixes;

    private final List<String> annotations;

    private final List<String> excludedAnnotations;

    private final boolean skipSynthetic;

    private JQuickMethodMatcher(Builder builder) {
        this.matchAll = builder.matchAll;
        this.names = Collections.unmodifiableList(new ArrayList<String>(builder.names));
        this.namePrefixes = Collections.unmodifiableList(new ArrayList<String>(builder.namePrefixes));
        this.excludedNames = Collections.unmodifiableList(new ArrayList<String>(builder.excludedNames));
        this.excludedPrefixes =
                Collections.unmodifiableList(new ArrayList<String>(builder.excludedPrefixes));
        this.annotations = Collections.unmodifiableList(new ArrayList<String>(builder.annotations));
        this.excludedAnnotations =
                Collections.unmodifiableList(new ArrayList<String>(builder.excludedAnnotations));
        this.skipSynthetic = builder.skipSynthetic;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builds a matcher that accepts every eligible method of an already selected class.
     */
    public static JQuickMethodMatcher all() {
        return ALL;
    }

    /**
     * Builds a matcher for an explicit list of method names.
     */
    public static JQuickMethodMatcher named(String... methodNames) {
        Builder builder = builder();
        for (String methodName : methodNames) {
            builder.matchName(methodName);
        }
        return builder.build();
    }

    /**
     * Evaluates the rule.
     *
     * @param name       method name
     * @param descriptor method descriptor
     * @param access     access flags
     * @param classMeta  owning class metadata, used for annotation based matching (may be {@code null})
     */
    public boolean matches(String name, String descriptor, int access, JQuickClassMeta classMeta) {
        if (name == null) {
            return false;
        }
        if (skipSynthetic && (access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0) {
            return false;
        }
        if (excludedNames.contains(name)) {
            return false;
        }
        for (String prefix : excludedPrefixes) {
            if (name.startsWith(prefix)) {
                return false;
            }
        }
        if (!excludedAnnotations.isEmpty()
                && matchesAnnotation(excludedAnnotations, name, descriptor, classMeta)) {
            return false;
        }
        if (!annotations.isEmpty() && matchesAnnotation(annotations, name, descriptor, classMeta)) {
            return true;
        }
        if (matchAll) {
            return true;
        }
        if (names.contains(name)) {
            return true;
        }
        for (String prefix : namePrefixes) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesAnnotation(List<String> candidates, String name, String descriptor,
                                      JQuickClassMeta classMeta) {
        if (classMeta == null) {
            return false;
        }
        com.jquick.asm.core.JQuickMethodInfo method = classMeta.findMethod(name, descriptor);
        if (method == null) {
            return false;
        }
        for (String annotation : candidates) {
            if (classMeta.methodHasAnnotation(method, annotation)) {
                return true;
            }
        }
        return false;
    }

    public boolean isMatchAll() {
        return matchAll;
    }

    public List<String> getNames() {
        return names;
    }

    public List<String> getAnnotations() {
        return annotations;
    }

    @Override
    public String toString() {
        if (matchAll) {
            return "JQuickMethodMatcher{ALL}";
        }
        return "JQuickMethodMatcher{names=" + names
                + ", prefixes=" + namePrefixes
                + ", annotations=" + annotations
                + ", excluded=" + excludedNames + '}';
    }

    /**
     * Builder.
     */
    public static final class Builder {

        private final List<String> names = new ArrayList<String>();
        private final List<String> namePrefixes = new ArrayList<String>();
        private final List<String> excludedNames = new ArrayList<String>(DEFAULT_EXCLUDED);
        private final List<String> excludedPrefixes = new ArrayList<String>(
                Collections.singletonList("lambda$"));
        private final List<String> annotations = new ArrayList<String>();
        private final List<String> excludedAnnotations = new ArrayList<String>();
        private boolean matchAll;
        private boolean skipSynthetic = true;

        public Builder matchAll() {
            this.matchAll = true;
            return this;
        }

        public Builder matchName(String methodName) {
            if (JQuickStringUtil.isNotBlank(methodName)) {
                names.add(methodName.trim());
            }
            return this;
        }

        public Builder matchNames(List<String> methodNames) {
            if (methodNames != null) {
                for (String methodName : methodNames) {
                    matchName(methodName);
                }
            }
            return this;
        }

        public Builder matchPrefix(String prefix) {
            if (JQuickStringUtil.isNotBlank(prefix)) {
                namePrefixes.add(prefix.trim());
            }
            return this;
        }

        public Builder matchAnnotation(String annotation) {
            if (JQuickStringUtil.isNotBlank(annotation)) {
                annotations.add(annotation.trim());
            }
            return this;
        }

        /**
         * Excludes every method carrying the annotation, e.g. {@code @JQuickIgnored}.
         */
        public Builder excludeAnnotation(String annotation) {
            if (JQuickStringUtil.isNotBlank(annotation)) {
                excludedAnnotations.add(annotation.trim());
            }
            return this;
        }

        /**
         * Removes a name from the default exclusion list, e.g. to trace {@code main}.
         */
        public Builder include(String methodName) {
            excludedNames.remove(methodName);
            return this;
        }

        public Builder exclude(String... methodNames) {
            if (methodNames != null) {
                for (String methodName : methodNames) {
                    if (JQuickStringUtil.isNotBlank(methodName)) {
                        excludedNames.add(methodName.trim());
                    }
                }
            }
            return this;
        }

        public Builder excludePrefix(String prefix) {
            if (JQuickStringUtil.isNotBlank(prefix)) {
                excludedPrefixes.add(prefix.trim());
            }
            return this;
        }

        public Builder skipSynthetic(boolean value) {
            this.skipSynthetic = value;
            return this;
        }

        public JQuickMethodMatcher build() {
            return new JQuickMethodMatcher(this);
        }
    }
}
