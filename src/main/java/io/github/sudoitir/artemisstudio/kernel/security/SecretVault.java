package io.github.sudoitir.artemisstudio.kernel.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * Envelope encryption of every stored secret with AES-256-GCM (ADR-0009, ADR-0132).
 *
 * <p>Each {@link #seal} draws a fresh data key (DEK), encrypts the plaintext with it under the caller's additional
 * authenticated data (so a blob cannot be moved to another row), and wraps the DEK under a key-encryption key (KEK)
 * from the {@link KeyProvider}. The blob is self-describing:
 *
 * <pre>
 * 0x01 | kekVersion (int32) | wrapNonce (12) | wrapped DEK (32 + 16 tag) | nonce (12) | ciphertext + tag
 * </pre>
 *
 * <p>The wrap has its own AAD, {@code "dek|" + kekVersion}, so a wrapped key cannot be relabelled to another
 * version. {@link #rewrap} moves a blob to another KEK without touching the ciphertext or needing the row AAD.
 * New secrets are wrapped with the stored current version ({@link SecretKeyState}). Key bytes are never logged or
 * put in a message.
 *
 * <p>A replica seals only with a current version it has confirmed from the database within
 * {@link #REFRESH_INTERVAL}: {@link #seal} reads it again when the last confirmation is older, and refuses when it
 * cannot, so a replica cut off from the database or the provider cannot go on writing under a key that a rotation
 * has replaced.
 */
@Component
public class SecretVault implements SmartInitializingSingleton {

    /** How often each replica reads the stored current version again (ADR-0132 D5). */
    public static final Duration REFRESH_INTERVAL = Duration.ofSeconds(10);

    /** A version missing from the keyring asks the provider again at most this often. */
    static final Duration RELOAD_BACKOFF = Duration.ofSeconds(30);

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final byte FORMAT = 1;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int WRAPPED_DEK_BYTES = Keyring.KEY_BYTES + TAG_BITS / 8;
    private static final int WRAP_NONCE_AT = 1 + Integer.BYTES;
    private static final int WRAPPED_AT = WRAP_NONCE_AT + NONCE_BYTES;
    private static final int NONCE_AT = WRAPPED_AT + WRAPPED_DEK_BYTES;
    private static final int CIPHERTEXT_AT = NONCE_AT + NONCE_BYTES;

    /** The shortest well-formed blob: header, wrapped DEK, nonce and an empty ciphertext's tag. */
    public static final int MIN_BLOB_BYTES = CIPHERTEXT_AT + TAG_BITS / 8;

    private final KeyProvider provider;
    private final SecretKeyState state;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<Integer, Instant> reloadedFor = new ConcurrentHashMap<>();
    private volatile Keyring keyring;
    private volatile int currentVersion;
    private volatile Instant confirmedAt;

    /** Fails when the provider cannot deliver a valid keyring. */
    public SecretVault(KeyProvider provider, SecretKeyState state, Clock clock) {
        this.provider = provider;
        this.state = state;
        this.clock = clock;
        this.keyring = provider.load();
    }

    /** Once the schema exists: reads (or stores) the current version, and fails startup if the keyring lacks it. */
    @Override
    public void afterSingletonsInstantiated() {
        refreshCurrentVersion();
    }

    /** The KEK version new secrets are wrapped with. */
    public int currentKekVersion() {
        return currentVersion;
    }

    /** Reads the stored current version again, for a rotation that changed it. */
    public int refreshCurrentVersion() {
        int version = state.currentOrInit(keyring.highest());
        if (lookup(version).isEmpty()) {
            throw new IllegalStateException("Secret key provider '" + provider.name() + "' does not hold key version "
                    + version + ", the current version of stored secrets.");
        }
        currentVersion = version;
        confirmedAt = clock.instant();
        return version;
    }

    /** The value of {@code artemis-studio.secrets.provider} that supplies the keys. */
    public String providerName() {
        return provider.name();
    }

    /** The keys as last loaded; {@link #reloadKeyring()} asks the provider again. */
    public Keyring keyring() {
        return keyring;
    }

    /** Asks the provider for its keys again (a new version may have been added) and returns them. */
    public Keyring reloadKeyring() {
        keyring = provider.load();
        return keyring;
    }

    /**
     * The current version, read from the database again when the last confirmation is older than
     * {@link #REFRESH_INTERVAL}, so a seal never relies on the refresh job and never uses a version a rotation has
     * replaced longer ago than that.
     */
    private int confirmedVersion() {
        Instant confirmed = confirmedAt;
        if (confirmed != null && !clock.instant().isAfter(confirmed.plus(REFRESH_INTERVAL))) {
            return currentVersion;
        }
        synchronized (this) {
            confirmed = confirmedAt;
            if (confirmed != null && !clock.instant().isAfter(confirmed.plus(REFRESH_INTERVAL))) {
                return currentVersion;
            }
            try {
                return refreshCurrentVersion();
            } catch (RuntimeException e) {
                throw new IllegalStateException(
                        "Cannot seal a secret: the current key version could not be confirmed from the database.", e);
            }
        }
    }

    /**
     * @throws IllegalStateException when the current version cannot be confirmed from the database, or the keyring
     *     lacks it
     */
    public byte[] seal(String aad, String plaintext) {
        int version = confirmedVersion();
        byte[] dek = new byte[Keyring.KEY_BYTES];
        random.nextBytes(dek);
        byte[] nonce = randomNonce();
        try {
            SecretKey kek = keyring.get(version)
                    .orElseThrow(() -> new IllegalStateException("Cannot seal a secret: provider '" + provider.name()
                            + "' does not hold the current key version " + version + "."));
            byte[] ciphertext = gcm(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(dek, "AES"),
                    nonce,
                    aad,
                    plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] wrapNonce = randomNonce();
            byte[] wrapped = gcm(Cipher.ENCRYPT_MODE, kek, wrapNonce, wrapAad(version), dek);
            return ByteBuffer.allocate(CIPHERTEXT_AT + ciphertext.length)
                    .put(FORMAT)
                    .putInt(version)
                    .put(wrapNonce)
                    .put(wrapped)
                    .put(nonce)
                    .put(ciphertext)
                    .array();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt secret", e);
        }
    }

    /**
     * @throws SecretDecryptException if the blob is malformed or tampered with, its key version is unknown to the
     *     provider, or {@code aad} does not match the row it was sealed for
     */
    public String open(String aad, byte[] blob) {
        try {
            SecretKey dek = new SecretKeySpec(unwrap(blob), "AES");
            byte[] nonce = Arrays.copyOfRange(blob, NONCE_AT, CIPHERTEXT_AT);
            byte[] ciphertext = Arrays.copyOfRange(blob, CIPHERTEXT_AT, blob.length);
            return new String(gcm(Cipher.DECRYPT_MODE, dek, nonce, aad, ciphertext), StandardCharsets.UTF_8);
        } catch (SecretDecryptException e) {
            throw e;
        } catch (Exception e) {
            throw new SecretDecryptException("Failed to decrypt secret for " + aad, e);
        }
    }

    /** The blob wrapped under KEK {@code targetVersion}; the ciphertext bytes are copied unchanged. */
    public byte[] rewrap(byte[] blob, int targetVersion) {
        try {
            byte[] dek = unwrap(blob);
            byte[] wrapNonce = randomNonce();
            byte[] wrapped = gcm(Cipher.ENCRYPT_MODE, kek(targetVersion), wrapNonce, wrapAad(targetVersion), dek);
            byte[] out = blob.clone();
            ByteBuffer.wrap(out)
                    .position(1)
                    .putInt(targetVersion)
                    .put(wrapNonce)
                    .put(wrapped);
            return out;
        } catch (SecretDecryptException e) {
            throw e;
        } catch (Exception e) {
            throw new SecretDecryptException("Failed to re-wrap secret", e);
        }
    }

    /** The KEK version a blob is wrapped under. */
    public static int kekVersion(byte[] blob) {
        requireWellFormed(blob);
        return ByteBuffer.wrap(blob).getInt(1);
    }

    /** The AAD of a secret that belongs to a cluster row: {@code clusterId|kind}. */
    public static String aad(UUID clusterId, String kind) {
        return clusterId + "|" + kind;
    }

    private byte[] unwrap(byte[] blob) throws GeneralSecurityException {
        int version = kekVersion(blob);
        byte[] wrapNonce = Arrays.copyOfRange(blob, WRAP_NONCE_AT, WRAPPED_AT);
        byte[] wrapped = Arrays.copyOfRange(blob, WRAPPED_AT, NONCE_AT);
        return gcm(Cipher.DECRYPT_MODE, kek(version), wrapNonce, wrapAad(version), wrapped);
    }

    /** A missing version asks the provider again, at most once per {@link #RELOAD_BACKOFF}. */
    private Optional<SecretKey> lookup(int version) {
        Optional<SecretKey> key = keyring.get(version);
        if (key.isPresent()) {
            return key;
        }
        Instant now = clock.instant();
        Instant last = reloadedFor.get(version);
        if (last != null && now.isBefore(last.plus(RELOAD_BACKOFF))) {
            return Optional.empty();
        }
        reloadedFor.put(version, now);
        return reloadKeyring().get(version);
    }

    /** A missing version fails naming the version. */
    private SecretKey kek(int version) {
        return lookup(version)
                .orElseThrow(() -> new SecretDecryptException(
                        "Secret is sealed under key version " + version + ", which provider '" + provider.name()
                                + "' does not hold.",
                        null));
    }

    private static void requireWellFormed(byte[] blob) {
        if (blob == null || blob.length < CIPHERTEXT_AT + TAG_BITS / 8 || blob[0] != FORMAT) {
            throw new SecretDecryptException("Sealed secret is malformed.", null);
        }
    }

    private static String wrapAad(int version) {
        return "dek|" + version;
    }

    private byte[] randomNonce() {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        return nonce;
    }

    private static byte[] gcm(int mode, SecretKey key, byte[] nonce, String aad, byte[] input)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        return cipher.doFinal(input);
    }

    public static final class SecretDecryptException extends RuntimeException {
        SecretDecryptException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
