package io.github.sudoitir.artemisstudio.feature.identitylocal.mfa;

import io.github.sudoitir.artemisstudio.kernel.security.SecretVault;
import io.github.sudoitir.artemisstudio.kernel.security.TableSealedStore;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * A user's TOTP secret in {@code local_totp}, sealed by {@link SecretVault} under the AAD
 * {@code local_totp:<user id>} so a blob cannot be moved to another user or used as another
 * kind of secret. A new secret waits in {@code local_totp_pending} and never replaces the active one
 * until a code from it is confirmed. Every statement that decides something is one atomic statement, so
 * concurrent requests cannot both win. Both tables are {@link TableSealedStore}s, so a key rotation
 * re-wraps the secrets and they stay readable when a key version is retired (ADR-0132).
 */
@Component
@RequiredArgsConstructor
class TotpStore {

    private final JdbcClient jdbc;
    private final SecretVault vault;

    private static String aad(UUID userId) {
        return "local_totp:" + userId;
    }

    boolean hasActive(UUID userId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM local_totp WHERE user_id = :id)")
                .param("id", userId)
                .query(Boolean.class)
                .single();
    }

    Optional<byte[]> active(UUID userId) {
        return read("local_totp", userId);
    }

    Optional<byte[]> pending(UUID userId) {
        return read("local_totp_pending", userId);
    }

    private Optional<byte[]> read(String table, UUID userId) {
        return jdbc.sql("SELECT sealed FROM " + table + " WHERE user_id = :id")
                .param("id", userId)
                .query(byte[].class)
                .optional()
                .map(blob -> Base32.decode(vault.open(aad(userId), blob)));
    }

    /** Keep {@code base32Secret} as the secret waiting to be confirmed, replacing any earlier one that was not. */
    void savePending(UUID userId, String base32Secret) {
        jdbc.sql("""
                        INSERT INTO local_totp_pending (user_id, sealed) VALUES (:id, :sealed)
                        ON CONFLICT (user_id) DO UPDATE SET sealed = EXCLUDED.sealed, created_at = now()
                        """)
                .param("id", userId)
                .param("sealed", vault.seal(aad(userId), base32Secret))
                .update();
    }

    /** Forget the user's authenticator app, active and pending. */
    void remove(UUID userId) {
        jdbc.sql("DELETE FROM local_totp WHERE user_id = :id")
                .param("id", userId)
                .update();
        jdbc.sql("DELETE FROM local_totp_pending WHERE user_id = :id")
                .param("id", userId)
                .update();
    }

    /**
     * Make the pending secret the active one; {@code step} is the step of the code that confirmed it,
     * so that code cannot then sign in. Returns whether there was a pending secret to activate.
     */
    boolean activate(UUID userId, long step) {
        return jdbc.sql("""
                        WITH moved AS (DELETE FROM local_totp_pending WHERE user_id = :id RETURNING user_id, sealed)
                        INSERT INTO local_totp (user_id, sealed, last_step)
                        SELECT user_id, sealed, :step FROM moved
                        ON CONFLICT (user_id) DO UPDATE SET
                            sealed = EXCLUDED.sealed,
                            confirmed_at = now(),
                            last_step = GREATEST(local_totp.last_step, EXCLUDED.last_step)
                        """).param("id", userId).param("step", step).update() == 1;
    }

    /**
     * Accept {@code step} once: true only for the request that moves {@code last_step} forward, so
     * the same code, or an older one, cannot be used again, even concurrently.
     */
    boolean advance(UUID userId, long step) {
        return jdbc.sql("UPDATE local_totp SET last_step = :step WHERE user_id = :id AND last_step < :step")
                        .param("id", userId)
                        .param("step", step)
                        .update()
                == 1;
    }

    /** The active secrets, for key rotation (ADR-0132). */
    @Component
    static class ActiveSealed extends TableSealedStore {

        ActiveSealed(JdbcTemplate jdbc) {
            super(jdbc, "local_totp", "user_id");
        }
    }

    /** The secrets waiting to be confirmed, for key rotation (ADR-0132). */
    @Component
    static class PendingSealed extends TableSealedStore {

        PendingSealed(JdbcTemplate jdbc) {
            super(jdbc, "local_totp_pending", "user_id");
        }
    }
}
