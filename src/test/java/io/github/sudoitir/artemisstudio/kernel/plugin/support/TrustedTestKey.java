package io.github.sudoitir.artemisstudio.kernel.plugin.support;

import org.springframework.jdbc.core.JdbcTemplate;

/** Pins {@link TestSigningKeys#PUBLISHER} as a trusted publisher key, so the default signed test jars may activate. */
public final class TrustedTestKey {

    private TrustedTestKey() {}

    public static void trust(JdbcTemplate jdbc) {
        trust(jdbc, TestSigningKeys.PUBLISHER, "Test publisher");
    }

    public static void trust(JdbcTemplate jdbc, TestSigningKeys.Key key, String name) {
        jdbc.update(
                """
                INSERT INTO plugin_trusted_key (fingerprint, name, subject, public_key, added_by)
                VALUES (?, ?, ?, ?, 'test') ON CONFLICT DO NOTHING
                """,
                key.fingerprint(),
                name,
                key.certificate().getSubjectX500Principal().getName(),
                key.certificate().getPublicKey().getEncoded());
    }
}
