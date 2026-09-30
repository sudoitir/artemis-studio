package io.github.sudoitir.artemisstudio.kernel.plugin.internal.trust;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.artemisstudio.kernel.plugin.internal.host.PluginRefusedException;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.trust.TrustDecision.Status;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.validation.Signer;
import io.github.sudoitir.artemisstudio.kernel.plugin.support.TestSigningKeys;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** {@link PluginTrust} against a real Postgres: pinned keys, the decision, and the allowance. */
class PluginTrustIT extends PostgresIntegrationTest {

    private static final Signer PUBLISHER = Signer.of(TestSigningKeys.PUBLISHER.certificate());

    @Autowired
    PluginTrust trust;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void noKeys() {
        jdbc.update("DELETE FROM plugin_trusted_key");
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM plugin_trusted_key");
        jdbc.update("UPDATE plugin_trust_policy SET allow_unverified = false, changed_at = NULL, changed_by = NULL");
    }

    @Test
    void addedKeyIsTrustedAndRemovedKeyIsNot() {
        assertThat(trust.decide(PUBLISHER).status()).isEqualTo(Status.UNTRUSTED);

        var added = trust.add("Acme", PUBLISHER, "admin");
        var decision = trust.decide(PUBLISHER);

        assertThat(added.fingerprint()).isEqualTo(TestSigningKeys.PUBLISHER.fingerprint());
        assertThat(added.addedBy()).isEqualTo("admin");
        assertThat(added.addedAt()).isNotNull();
        assertThat(decision.status()).isEqualTo(Status.TRUSTED);
        assertThat(decision.keyName()).isEqualTo("Acme");
        assertThat(decision.fingerprint()).isEqualTo(PUBLISHER.fingerprint());
        assertThat(trust.keys()).extracting(PluginTrust.TrustedKey::name).containsExactly("Acme");

        assertThat(trust.remove(PUBLISHER.fingerprint())).isTrue();
        assertThat(trust.remove(PUBLISHER.fingerprint())).isFalse();
        assertThat(trust.decide(PUBLISHER.fingerprint(), PUBLISHER.subject()).status())
                .isEqualTo(Status.UNTRUSTED);
        assertThat(trust.keys()).isEmpty();
    }

    @Test
    void anUnsignedJarIsUnsigned() {
        assertThat(trust.decide((Signer) null).status()).isEqualTo(Status.UNSIGNED);
        assertThat(trust.decide(null, null).status()).isEqualTo(Status.UNSIGNED);
    }

    @Test
    void theAllowanceDefaultsToFalseAndRoundTrips() {
        assertThat(trust.allowUnverified()).isFalse();

        trust.setAllowUnverified(true, "admin");
        assertThat(trust.allowUnverified()).isTrue();
        assertThat(jdbc.queryForObject("SELECT changed_by FROM plugin_trust_policy", String.class))
                .isEqualTo("admin");

        trust.setAllowUnverified(false, "admin");
        assertThat(trust.allowUnverified()).isFalse();
    }

    @Test
    void addingTheSameFingerprintTwiceIsRefused() {
        trust.add("Acme", PUBLISHER, "admin");

        assertThatThrownBy(() -> trust.add("Acme again", PUBLISHER, "admin"))
                .isInstanceOfSatisfying(
                        PluginRefusedException.class,
                        e -> assertThat(e.violations()).extracting("code").containsExactly("key-exists"));
        assertThat(trust.keys()).hasSize(1);
    }
}
