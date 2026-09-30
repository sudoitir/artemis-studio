package io.github.sudoitir.artemisstudio.kernel.security;

import java.util.Base64;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/** The key-encryption keys a {@link KeyProvider} holds, by version. Immutable; never printed. */
public record Keyring(SortedMap<Integer, SecretKey> keys) {

    static final int KEY_BYTES = 32;

    public Keyring {
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("A keyring needs at least one key.");
        }
        keys = java.util.Collections.unmodifiableSortedMap(new TreeMap<>(keys));
    }

    public int highest() {
        return keys.lastKey();
    }

    public Optional<SecretKey> get(int version) {
        return Optional.ofNullable(keys.get(version));
    }

    /**
     * Decodes one base64 AES-256 key. The message names the provider and the version or file, never the bytes.
     */
    public static SecretKey parse(String provider, String label, String base64) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Secret key provider '" + provider + "': " + label
                    + " is not valid base64. Expected base64 of " + KEY_BYTES + " bytes.");
        }
        if (decoded.length != KEY_BYTES) {
            throw new IllegalStateException("Secret key provider '" + provider + "': " + label + " must decode to "
                    + "exactly " + KEY_BYTES + " bytes (32-byte key required, got " + decoded.length
                    + "). Use `openssl rand -base64 32`.");
        }
        return new SecretKeySpec(decoded, "AES");
    }
}
