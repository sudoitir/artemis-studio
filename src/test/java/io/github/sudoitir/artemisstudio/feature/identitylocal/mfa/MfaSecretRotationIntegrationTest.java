package io.github.sudoitir.artemisstudio.feature.identitylocal.mfa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.kernel.security.KeyProvider;
import io.github.sudoitir.artemisstudio.kernel.security.Keyring;
import io.github.sudoitir.artemisstudio.kernel.security.SealedStore;
import io.github.sudoitir.artemisstudio.kernel.security.SecretRotations;
import io.github.sudoitir.artemisstudio.kernel.security.SecretVault;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * A key rotation does not break second factors (ADR-0143, ADR-0132): the authenticator secrets and the recovery codes'
 * HMAC key are sealed stores, so the sweep re-wraps them to the new key version and a secret still opens, and a
 * recovery code issued before still spends, afterwards. The keyring holds versions 1 and 2, and the test puts the
 * shared database back on version 1, since other contexts hold only that key.
 */
class MfaSecretRotationIntegrationTest extends PostgresIntegrationTest {

    private static final String KEY_1 = "YXJ0ZW1pcy1zdHVkaW8tdGVzdC1rZXktMzJieXRlcyE=";
    private static final String KEY_2 = Base64.getEncoder().encodeToString(new byte[32]);

    @TestConfiguration
    static class TwoKeys {

        @Bean
        @Primary
        KeyProvider twoKeys() {
            return new KeyProvider() {
                @Override
                public Keyring load() {
                    return new Keyring(new TreeMap<>(Map.of(
                            1, Keyring.parse("env", "key version 1", KEY_1),
                            2, Keyring.parse("env", "key version 2", KEY_2))));
                }

                @Override
                public Optional<String> secret(String name) {
                    return Optional.empty();
                }

                @Override
                public String name() {
                    return "env";
                }
            };
        }
    }

    @Autowired
    SecretRotations rotations;

    @Autowired
    SecretVault vault;

    @Autowired
    List<SealedStore> stores;

    @Autowired
    TotpStore totp;

    @Autowired
    RecoveryCodes recoveryCodes;

    @Autowired
    AppUserRepository users;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JdbcClient jdbcClient;

    private UUID userId;

    @BeforeEach
    void startOnVersionOne() {
        jdbc.update("DELETE FROM secret_rotation");
        jdbc.update("UPDATE secret_key_state SET current_kek_version = 1");
        vault.refreshCurrentVersion();
        ReflectionTestUtils.setField(rotations, "counts", null);
        userId = users.save(AppUserEntity.local("rot-mfa-" + UUID.randomUUID(), "rot@example.test", "x"))
                .getId();
    }

    @AfterEach
    void putBackOnVersionOne() {
        jdbc.update("DELETE FROM app_user WHERE id = ?", userId);
        jdbc.update("DELETE FROM secret_rotation");
        for (SealedStore store : stores) {
            store.rewrapBatch(null, Integer.MAX_VALUE, Integer.MAX_VALUE, blob -> vault.rewrap(blob, 1));
        }
        jdbc.update("UPDATE secret_key_state SET current_kek_version = 1");
        vault.refreshCurrentVersion();
    }

    private int versionOf(String table, String where, Object arg) {
        byte[] blob = jdbc.queryForObject("SELECT sealed FROM " + table + " WHERE " + where, byte[].class, arg);
        return SecretVault.kekVersion(blob);
    }

    @Test
    void authenticatorSecretsAndRecoveryCodesSurviveAKeyRotation() {
        String secret = Base32.encode("12345678901234567890".getBytes());
        totp.savePending(userId, secret);
        totp.activate(userId, 1);
        List<String> codes = recoveryCodes.issue(userId);
        assertThat(versionOf("local_totp", "user_id = ?", userId)).isEqualTo(1);
        assertThat(versionOf("local_recovery_key", "singleton = ?", true)).isEqualTo(1);

        rotations.start("test");
        jdbc.update("UPDATE secret_rotation SET started_at = started_at - interval '1 hour' WHERE status = 'RUNNING'");
        rotations.sweep();

        assertThat(versionOf("local_totp", "user_id = ?", userId)).isEqualTo(2);
        assertThat(versionOf("local_recovery_key", "singleton = ?", true)).isEqualTo(2);
        assertThat(totp.active(userId).orElseThrow()).isEqualTo(Base32.decode(secret));
        // A key read again from the rotated blob, as after a restart, still matches the stored hashes.
        RecoveryCodes restarted = new RecoveryCodes(jdbcClient, new RecoveryKey(jdbc, vault));
        assertThat(restarted.spend(userId, codes.get(0))).isTrue();
        assertThat(recoveryCodes.spend(userId, codes.get(1))).isTrue();
    }
}
