package io.github.paohaijiao.jquick.agent.plugin.log;

import io.github.paohaijiao.jquick.agent.core.JQuickArgumentTransformer;
import io.github.paohaijiao.jquick.agent.core.JQuickInvocation;
import io.github.paohaijiao.jquick.agent.plugin.mask.JQuickSensitiveMasker;

/**
 * Rewrites the message argument of a logging call: prefixes it with the trace id and, when asked,
 * masks the sensitive fields inside it.
 *
 * <p>This is what turns "the request failed somewhere" into "the request failed, and here are its
 * eleven log lines" without touching a single logging statement: the transform is applied to the
 * {@code msg} argument of the logging method, in the same way a formatter would be, except that it
 * requires no change to the logging configuration, no MDC plumbing and no cooperation from the
 * application at all.
 *
 * <p>Only the first {@code String} argument is rewritten, which is the message by convention across
 * SLF4J, Log4j1, Log4j2 and {@code java.util.logging}. Parameterised messages
 * ({@code info("{} {}", a, b)}) keep working — only the template is prefixed.
 *
 * <h3>Two rules, one pass</h3>
 * Prefixing and masking are applied in a single rewrite because cloning the argument array twice per
 * log call is waste, and because the mask has to run on the message the operator will see — after the
 * prefix, so the prefix itself is never masked. Masking is opt-in
 * ({@code log.mask.enabled=true}): a matcher that guesses wrong would censor production logs, which is
 * why the defaults never touch a message.
 *
 * <h3>Why it checks the component label</h3>
 * Argument transformers are dispatched to every instrumented call, and the hook plan decides only
 * whether a class calls into the transform pipeline at all. Without a guard, this transformer would
 * also rewrite the arguments of a class that encryption or tenant rewriting had selected — prefixing
 * a payload that was about to be decrypted, or a SQL statement that was about to be parsed. The guard
 * makes the transformer act exactly on the calls its own plugin instrumented: component {@code log}.
 *
 * <p>The component label is used rather than the class name because the log plugin selects by
 * hierarchy — the application's logger classes have arbitrary names, but the label is a constant the
 * enhancer writes.
 *
 * @author Martin
 */
public final class JQuickLogTransformer implements JQuickArgumentTransformer {

    /**
     * Component label written by the log enhancer.
     */
    static final String COMPONENT = JQuickLogPlugin.NAME;

    private final boolean maskMessages;

    /**
     * Trace id injection only.
     */
    public JQuickLogTransformer() {
        this(false);
    }

    /**
     * @param maskMessages whether sensitive fields inside the message are masked as well
     */
    public JQuickLogTransformer(boolean maskMessages) {
        this.maskMessages = maskMessages;
    }

    @Override
    public Object[] transform(JQuickInvocation invocation, Object[] arguments) {
        if (arguments == null || arguments.length == 0 || !appliesTo(invocation)) {
            return arguments;
        }
        for (int i = 0; i < arguments.length; i++) {
            Object candidate = arguments[i];
            if (!(candidate instanceof String)) {
                continue;
            }
            String message = JQuickLogSupport.decorate((String) candidate);
            if (maskMessages) {
                message = JQuickSensitiveMasker.mask(message);
            }
            if (message.equals(candidate)) {
                return arguments;
            }
            Object[] replacement = arguments.clone();
            replacement[i] = message;
            return replacement;
        }
        return arguments;
    }

    private boolean appliesTo(JQuickInvocation invocation) {
        if (invocation == null) {
            return false;
        }
        String component = invocation.getComponent();
        return component != null && component.indexOf(COMPONENT) >= 0;
    }

    @Override
    public String name() {
        return "log-trace-id";
    }
}
