package io.github.paohaijiao.jquick.agent.plugin.crypto;

/**
 * Symmetric cipher used by the transparent encryption capability.
 *
 * <p>An interface rather than a hard dependency on a KMS or a specific algorithm: the agent's job is
 * to apply the transformation at the boundary, and the key management policy belongs to the
 * application. An implementation that delegates to a KMS, an HSM or a per-tenant key store plugs in
 * here without touching the instrumentation.
 *
 * @author Martin
 */
public interface JQuickDataCipher {

    /**
     * Encrypts a plaintext value.
     *
     * @param plaintext the value as the application produced it; never {@code null}
     * @return the encrypted representation, never {@code null}
     */
    String encrypt(String plaintext);

    /**
     * Decrypts a previously encrypted value.
     *
     * @param ciphertext the value as it arrived from the client; never {@code null}
     * @return the plaintext
     * @throws RuntimeException when the payload cannot be decrypted
     */
    String decrypt(String ciphertext);

    /**
     * Human readable identifier used in diagnostics.
     */
    default String name() {
        return getClass().getSimpleName();
    }
}
