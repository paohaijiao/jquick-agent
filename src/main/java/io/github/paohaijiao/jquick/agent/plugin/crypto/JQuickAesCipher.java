package io.github.paohaijiao.jquick.agent.plugin.crypto;

import io.github.paohaijiao.jquick.agent.support.JQuickLogger;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

/**
 * Reference AES cipher: {@code AES/CBC/PKCS5Padding} with a key derived from a passphrase.
 *
 * <p><b>Read this before using it in production.</b> This implementation exists so that the
 * transparent-encryption capability is usable and testable out of the box. It is a
 * <em>reference</em>, not a key-management solution:
 *
 * <ul>
 *   <li>the passphrase is turned into a 256-bit key with a single SHA-256 pass — no salt, no
 *       work factor. A real deployment derives from a KMS-managed secret or an HSM;</li>
 *   <li>the IV is derived deterministically from the key, so the same plaintext always produces the
 *       same ciphertext. That leaks equality between values; a production implementation must use a
 *       random per-message IV and store it alongside the ciphertext;</li>
 *   <li>no authentication tag, so ciphertext tampering is not detected. Use AES-GCM when integrity
 *       matters.</li>
 * </ul>
 *
 * <p>What it does give you is the right <b>shape</b>: an application that never has to call
 * {@code encrypt} or {@code decrypt} itself, because the agent applies them at the method boundary.
 * Swapping in a hardened {@link JQuickDataCipher} is then a one-line change.
 *
 * @author Martin
 */
public final class JQuickAesCipher implements JQuickDataCipher {

    private static final String TRANSFORMATION = "AES/CBC/PKCS5Padding";

    private static final String CHARSET = "UTF-8";

    private final SecretKeySpec key;

    private final IvParameterSpec iv;

    public JQuickAesCipher(String passphrase) {
        if (passphrase == null || passphrase.isEmpty()) {
            throw new IllegalArgumentException("passphrase must not be empty");
        }
        byte[] digest = sha256(passphrase);
        this.key = new SecretKeySpec(digest, "AES");
        this.iv = new IvParameterSpec(Arrays.copyOfRange(digest, 0, 16));
    }

    private static byte[] sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(value.getBytes(CHARSET));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    @Override
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, iv);
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(CHARSET));
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (Throwable ex) {
            throw new IllegalStateException("encryption failed", ex);
        }
    }

    @Override
    public String decrypt(String ciphertext) {
        if (ciphertext == null) {
            return null;
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, iv);
            byte[] decrypted = cipher.doFinal(Base64.getDecoder().decode(ciphertext));
            return new String(decrypted, CHARSET);
        } catch (Throwable ex) {
            // A value that is not ours must be passed through, not turned into a failure: the agent
            // cannot know whether a given argument was encrypted by a client or is plain data.
            JQuickLogger.debug("decryption skipped (payload is not a ciphertext): " + ex);
            return ciphertext;
        }
    }

    @Override
    public String name() {
        return "aes-cbc-reference";
    }
}
