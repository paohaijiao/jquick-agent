package io.github.paohaijiao.jquick.agent.plugin.crypto;

import io.github.paohaijiao.jquick.agent.core.*;
import io.github.paohaijiao.jquick.agent.support.JQuickLogger;
import io.github.paohaijiao.jquick.agent.trace.JQuickSpanType;

import java.util.ArrayList;
import java.util.List;

/**
 * Capability: transparent encryption and decryption at the method boundary.
 *
 * <p>Select the classes whose payload crosses a trust boundary — a remote facade, a callback that
 * receives an external token — and the agent decrypts the incoming {@code String} arguments and
 * encrypts the {@code String} return value. No business code changes, no wrapper DTO.
 *
 * <p>The cipher is pluggable; the bundled {@link JQuickAesCipher} is a reference implementation whose
 * limitations are documented on the class itself.
 *
 * @author Martin
 */
public final class JQuickCryptoPlugin implements JQuickPlugin {

    public static final String NAME = "crypto";

    private final List<JQuickClassEnhancer> enhancers = new ArrayList<JQuickClassEnhancer>();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public JQuickCategory category() {
        return JQuickCategory.DATA;
    }

    @Override
    public boolean isEnabled(JQuickConfig config) {
        return config.isPluginEnabled(NAME);
    }

    @Override
    public void initialize(JQuickEnvironment environment) {
        JQuickConfig config = environment.getConfig();
        List<String> includes = config.getList(JQuickConfig.CRYPTO_INCLUDES);
        JQuickDataCipher cipher = new JQuickAesCipher(config.get(JQuickConfig.CRYPTO_KEY, "jquick"));
        if (includes.isEmpty()) {
            JQuickLogger.warn("crypto plugin is enabled but " + JQuickConfig.CRYPTO_INCLUDES
                    + " is empty: no class will be encrypted");
        }
        // The same selection drives both the byte-code decision and the runtime decision, so the two
        // can never disagree about which classes are encrypted.
        JQuickCryptoTransformer transformer = new JQuickCryptoTransformer(cipher, includes);
        environment.addArgumentTransformer(transformer);
        environment.addResultTransformer(transformer);
        // The plan only materialises arguments and return values for classes this enhancer selects,
        // so the transformation never reaches a method the operator did not point it at.
        enhancers.add(new JQuickInstrumentEnhancer(
                NAME,
                JQuickClassMatcher.byName(includes.toArray(new String[0])),
                JQuickMethodMatcher.all(),
                JQuickSpanType.LOCAL,
                NAME,
                environment,
                // Requested per class rather than globally: only the classes the operator pointed at
                // pay for the argument array and the result boxing.
                JQuickHookPlan.builder().transformArguments().transformResult().build()));
    }

    @Override
    public List<JQuickClassEnhancer> enhancers() {
        return enhancers;
    }

    @Override
    public String description() {
        return "transparent argument decryption and result encryption";
    }
}
