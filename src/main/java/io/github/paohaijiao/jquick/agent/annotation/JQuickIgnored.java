package io.github.paohaijiao.jquick.agent.annotation;

import java.lang.annotation.*;

/**
 * Excludes a class or a method from instrumentation.
 *
 * <p>This is the escape hatch of the "zero intrusion" promise: when a package is instrumented
 * wholesale, a hot, trivial or sensitive method can be excluded declaratively, without touching the
 * {@code includes}/{@code excludes} configuration and without a redeploy of the agent rules.
 *
 * <pre>{@code
 * @JQuickIgnored
 * public String getUnsafeSecret() { ... }
 * }</pre>
 *
 * @author Martin
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface JQuickIgnored {

    /**
     * Why the element is excluded; purely documentation for the next reader of the code.
     */
    String value() default "";
}
