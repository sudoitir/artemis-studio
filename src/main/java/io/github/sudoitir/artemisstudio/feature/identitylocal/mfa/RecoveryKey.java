package io.github.sudoitir.artemisstudio.feature.identitylocal.mfa;

import io.github.sudoitir.artemisstudio.kernel.security.SecretVault;
import io.github.sudoitir.artemisstudio.kernel.security.TableSealedStore;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The HMAC key of the recovery codes (ADR-0143): 32 random bytes made once per installation and kept in
 * {@code local_recovery_key}, sealed by {@link SecretVault}. It is a secret of its own, not a key derived from a
 * key-encryption key, because a derived key would change when a key version is rotated or retired and take every
 * stored hash with it. Rotation re-wraps the blob (see {@link Sealed}); the key stays the same.
 */
@Component
@RequiredArgsConstructor
class RecoveryKey {

    private static final String AAD = "local_recovery_key";
    private static final int BYTES = 32;

    private final JdbcTemplate jdbc;
    private final SecretVault vault;
    private final SecureRandom random = new SecureRandom();
    private volatile SecretKeySpec key;

    /** The key, read (or made and stored on first use) once and kept for the life of the process. */
    SecretKeySpec get() {
        SecretKeySpec known = key;
        if (known != null) {
            return known;
        }
        synchronized (this) {
            if (key == null) {
                key = load();
            }
            return key;
        }
    }

    private SecretKeySpec load() {
        byte[] made = new byte[BYTES];
        random.nextBytes(made);
        // Two replicas starting together both try; the primary key lets one win and both read that one back.
        jdbc.update(
                "INSERT INTO local_recovery_key (sealed) VALUES (?) ON CONFLICT DO NOTHING",
                vault.seal(AAD, Base64.getEncoder().encodeToString(made)));
        byte[] sealed = jdbc.queryForObject("SELECT sealed FROM local_recovery_key", byte[].class);
        return new SecretKeySpec(Base64.getDecoder().decode(vault.open(AAD, sealed)), "HmacSHA256");
    }

    /** The sealed key, for key rotation (ADR-0132). */
    @Component
    static class Sealed extends TableSealedStore {

        Sealed(JdbcTemplate jdbc) {
            super(jdbc, "local_recovery_key", "singleton");
        }
    }
}
