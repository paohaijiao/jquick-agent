package io.github.paohaijiao.jquick.agent.annotation;

import java.lang.annotation.*;

/**
 * Opts a class or a method into method-level tracing.
 *
 * <p>Two instrumentation strategies are supported and this annotation drives the second one:
 * <ul>
 *   <li><b>package driven</b> (default) — everything matched by {@code includes} is traced;</li>
 *   <li><b>annotation driven</b> — only classes/methods carrying this annotation are traced, which
 *       is the right choice when a package is too broad to be instrumented wholesale.</li>
 * </ul>
 *
 * <p>The annotation is read from the <em>target</em> byte-code through jquick-asm's reader and never
 * takes part in the enhancement itself: it expresses the developer's intent, nothing more.
 *
 * <pre>{@code
 * @JQuickTrace
 * public class OrderService {
 *     public void pay(String user) { ... }
 * }
 * }</pre>
 *
 * @author Martin
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface JQuickTrace {

    /**
     * Optional logical operation name; defaults to the annotated element itself.
     */
    String value() default "";

    /**
     * Span type to assign: {@code ENTRY}, {@code EXIT} or {@code LOCAL}.
     */
    String type() default "LOCAL";

    /**
     * Logical component, e.g. {@code pay-service}.
     */
    String component() default "";
}
