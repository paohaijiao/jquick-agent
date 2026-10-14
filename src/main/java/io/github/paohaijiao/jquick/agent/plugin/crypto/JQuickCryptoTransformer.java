package io.github.paohaijiao.jquick.agent.plugin.crypto;

import io.github.paohaijiao.jquick.agent.core.JQuickArgumentTransformer;
import io.github.paohaijiao.jquick.agent.core.JQuickInvocation;
import io.github.paohaijiao.jquick.agent.core.JQuickResultTransformer;
import io.github.paohaijiao.jquick.agent.support.JQuickClassFilters;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Applies a {@link JQuickDataCipher} to the arguments and the return value of the instrumented
 * methods.
 *
 * <p>The transformation is symmetric and described from the application's point of view:
 * <ul>
 *   <li><b>inbound</b> — every {@code String} argument is decrypted, so the method body sees the
 *       plaintext although the caller sent a ciphertext;</li>
 *   <li><b>outbound</b> — a {@code String} return value is encrypted, so the caller receives a
 *       ciphertext although the body produced plaintext.</li>
 * </ul>
 *
 * <h3>Why this transformer filters by class itself</h3>
 * Argument and result transformers are registered on the environment, and the environment dispatches
 * <em>all</em> of them. The hook plan decides whether a given class calls into the transform pipeline
 * at all, but it does not decide <em>which</em> transformer should act on that class: the moment two
 * class-scoped data capabilities are enabled — encryption for the payment package, masking for the
 * customer package — a plan-driven design would run both on both, encrypting data that should have
 * been masked and vice versa.
 *
 * <p>Each transformer therefore owns its selection. It is the same decision the JDBC tenant rewriter
 * makes when it checks the component label, and it is the reason every transformer takes the
 * {@link JQuickInvocation}: the invocation is the only thing that knows which class is running.
 *
 * <p>An empty pattern list selects nothing. That asymmetry is deliberate — a plugin that lost its
 * configuration must be inert, never global.
 *
 * <h3>What this is not</h3>
 * It is not a substitute for transport security. The values still travel in the clear inside the
 * process, and a serialised object graph is not walked. Encrypting at the boundary is a
 * defence-in-depth layer for the case where the transport is trusted but the payload must not be
 * readable at rest or by an intermediary.
 *
 * @author Martin
 */
public final class JQuickCryptoTransformer implements JQuickArgumentTransformer, JQuickResultTransformer {

    private final JQuickDataCipher cipher;

    private final List<String> classPatterns;

    public JQuickCryptoTransformer(JQuickDataCipher cipher, List<String> classPatterns) {
        this.cipher = cipher;
        this.classPatterns = classPatterns == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(classPatterns));
    }

    public JQuickDataCipher getCipher() {
        return cipher;
    }

    public List<String> getClassPatterns() {
        return classPatterns;
    }

    @Override
    public Object[] transform(JQuickInvocation invocation, Object[] arguments) {
        if (arguments == null || !appliesTo(invocation)) {
            return arguments;
        }
        Object[] replacement = null;
        for (int i = 0; i < arguments.length; i++) {
            Object candidate = arguments[i];
            if (!(candidate instanceof String)) {
                continue;
            }
            String decrypted = cipher.decrypt((String) candidate);
            if (decrypted != null && !decrypted.equals(candidate)) {
                if (replacement == null) {
                    replacement = arguments.clone();
                }
                replacement[i] = decrypted;
            }
        }
        return replacement == null ? arguments : replacement;
    }

    @Override
    public Object transform(JQuickInvocation invocation, Object result) {
        if (!(result instanceof String) || !appliesTo(invocation)) {
            return result;
        }
        return cipher.encrypt((String) result);
    }

    /**
     * Whether this transformer owns the class currently executing.
     */
    private boolean appliesTo(JQuickInvocation invocation) {
        return invocation != null
                && JQuickClassFilters.matchesAnyPattern(invocation.getClassName(), classPatterns);
    }

    @Override
    public String name() {
        return "crypto[" + cipher.name() + "]";
    }
}
