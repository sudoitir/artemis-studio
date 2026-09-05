package io.github.sudoitir.artemisstudio.security;

import io.github.sudoitir.artemisstudio.config.ArtemisStudioProperties;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Encrypts and decrypts broker secrets at rest with AES-256-GCM (ADR-0009).
 *
 * <p>The root key comes from {@code artemis-studio.secret-key}
 * ({@code ARTEMIS_STUDIO_SECRET_KEY}); it must base64-decode to exactly 32 bytes
 * or this bean fails to construct and the application does not start. The key is
 * never logged, persisted, or echoed in an error.
 *
 * <p>Each {@link #encrypt} produces a fresh 12-byte nonce (stored beside the
 * ciphertext in {@code broker_credential.secret_nonce}). The additional
 * authenticated data is {@code clusterId + "|" + kind}, so a ciphertext cannot be
 * moved to a different cluster or credential kind without failing authentication.
 */
@Component
public class SecretVault {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretVault(ArtemisStudioProperties properties) {
        this.key = loadKey(properties.secretKey());
    }

    private static SecretKeySpec loadKey(String configured) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("ARTEMIS_STUDIO_SECRET_KEY (artemis-studio.secret-key) is not set. "
                    + "Provide base64 of 32 random bytes, e.g. `openssl rand -base64 32`.");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(configured.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "ARTEMIS_STUDIO_SECRET_KEY is not valid base64. Expected base64 of 32 bytes.");
        }
        if (decoded.length != KEY_BYTES) {
            throw new IllegalStateException("ARTEMIS_STUDIO_SECRET_KEY must decode to exactly " + KEY_BYTES
                    + " bytes (got " + decoded.length + "). Use `openssl rand -base64 32`.");
        }
        return new SecretKeySpec(decoded, "AES");
    }

    /** Ciphertext (with the GCM tag appended) plus the nonce used to produce it. */
    public record Sealed(byte[] ciphertext, byte[] nonce) {}

    public Sealed encrypt(UUID clusterId, String kind, String plaintext) {
        return encrypt(aad(clusterId, kind), plaintext);
    }

    /**
     * @throws SecretDecryptException if the key is wrong, the ciphertext was
     *     tampered with, or the {@code (clusterId, kind)} does not match the AAD
     *     the ciphertext was sealed with.
     */
    public String decrypt(UUID clusterId, String kind, byte[] ciphertext, byte[] nonce) {
        return decrypt(aad(clusterId, kind), ciphertext, nonce, "cluster " + clusterId + " kind " + kind);
    }

    /**
     * For secrets that do not belong to a cluster row — a notification channel's
     * webhook URL or signing secret (ADR-0036). {@code aad} should be a stable
     * identifier of the owning row plus a kind, e.g. {@code channelId + "|" +
     * kind}, following the same "AAD binds ciphertext to its row" principle as
     * the cluster-scoped overload; only the shape of the identifier generalizes.
     */
    public Sealed encrypt(String aad, String plaintext) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return new Sealed(ct, nonce);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt secret", e);
        }
    }

    /** @throws SecretDecryptException if the key is wrong, the ciphertext was tampered with, or {@code aad} does not match. */
    public String decrypt(String aad, byte[] ciphertext, byte[] nonce) {
        return decrypt(aad, ciphertext, nonce, aad);
    }

    private String decrypt(String aad, byte[] ciphertext, byte[] nonce, String context) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new SecretDecryptException("Failed to decrypt secret for " + context, e);
        }
    }

    private static String aad(UUID clusterId, String kind) {
        return clusterId + "|" + kind;
    }

    public static final class SecretDecryptException extends RuntimeException {
        SecretDecryptException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
