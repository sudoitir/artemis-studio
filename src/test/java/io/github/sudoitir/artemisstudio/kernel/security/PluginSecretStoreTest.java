package io.github.sudoitir.artemisstudio.kernel.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.artemisstudio.kernel.audit.internal.persistence.AuditEventEntity;
import io.github.sudoitir.artemisstudio.kernel.audit.internal.persistence.AuditEventRepository;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginPurged;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.PluginSecretEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.PluginSecretRepository;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;

/** A plugin's secrets (ADR-0111): sealed, isolated, audited by name only, gone on purge. */
class PluginSecretStoreTest extends PostgresIntegrationTest {

    @Autowired
    PluginSecretStore store;

    @Autowired
    PluginSecretRepository rows;

    @Autowired
    AuditEventRepository auditEvents;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    TransactionTemplate tx;

    private final String run = UUID.randomUUID().toString().substring(0, 8);

    private PluginSecrets secretsOf(String pluginId) {
        return (PluginSecrets) store.beansFor(pluginId).get(PluginSecretStore.BEAN_NAME);
    }

    @Test
    void aSecretIsReadBackOnlyByThePluginThatStoredIt() {
        PluginSecrets a = secretsOf("acme-a" + run);
        PluginSecrets b = secretsOf("acme-b" + run);
        a.put("api-token", "s3cr3t-value");

        assertThat(a.get("api-token")).contains("s3cr3t-value");
        assertThat(b.get("api-token")).isEmpty();
        assertThat(b.list()).isEmpty();
        assertThat(a.list()).extracting(PluginSecrets.SecretInfo::name).containsExactly("api-token");
    }

    @Test
    void aValueIsStoredEncryptedAndReplacingItKeepsOneRow() {
        String plugin = "acme-enc" + run;
        PluginSecrets secrets = secretsOf(plugin);
        secrets.put("token", "first-value");
        secrets.put("token", "second-value");

        assertThat(secrets.get("token")).contains("second-value");
        PluginSecretEntity row = rows.findByPluginIdAndName(plugin, "token").orElseThrow();
        assertThat(new String(row.getCiphertext(), java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain("second-value");
        assertThat(rows.findByPluginIdOrderByName(plugin)).hasSize(1);
    }

    @Test
    void aCiphertextCopiedToAnotherPluginDoesNotDecrypt() {
        String owner = "acme-own" + run;
        String thief = "acme-thf" + run;
        secretsOf(owner).put("token", "owner-only");
        PluginSecretEntity original = rows.findByPluginIdAndName(owner, "token").orElseThrow();

        PluginSecretEntity copy = new PluginSecretEntity(thief, "token");
        copy.setCiphertext(original.getCiphertext());
        copy.setNonce(original.getNonce());
        copy.setUpdatedAt(Instant.now());
        rows.save(copy);

        assertThatThrownBy(() -> secretsOf(thief).get("token")).isInstanceOf(SecretVault.SecretDecryptException.class);
    }

    @Test
    void purgingAPluginDeletesItsSecretsAndNoOneElses() {
        String purged = "acme-prg" + run;
        String kept = "acme-kpt" + run;
        secretsOf(purged).put("a", "1");
        secretsOf(purged).put("b", "2");
        secretsOf(kept).put("a", "3");

        tx.executeWithoutResult(s -> events.publishEvent(new PluginPurged(purged)));

        assertThat(secretsOf(purged).list()).isEmpty();
        assertThat(secretsOf(kept).get("a")).contains("3");
    }

    @Test
    void writesAndDeletesAreAuditedByNameAndNeverCarryTheValue() {
        String plugin = "acme-aud" + run;
        secretsOf(plugin).put("token", "never-in-audit");
        secretsOf(plugin).delete("token");

        var audited = auditEvents.findByTargetTypeAndTargetNameOrderByTsDesc(
                "PLUGIN_SECRET", plugin + "/token", PageRequest.of(0, 10));
        assertThat(audited)
                .extracting(AuditEventEntity::getAction)
                .containsExactly("PLUGIN_SECRET_DELETE", "PLUGIN_SECRET_SET");
        assertThat(audited)
                .allSatisfy(e -> assertThat(String.valueOf(e.getParams())).doesNotContain("never-in-audit"));
    }

    @Test
    void aSecretNameCannotSmuggleASeparatorAndAnOversizedValueIsNotEchoed() {
        PluginSecrets secrets = secretsOf("acme-val" + run);
        assertThatThrownBy(() -> secrets.put("a|b", "x")).isInstanceOf(IllegalArgumentException.class);
        String huge = "y".repeat(70 * 1024);
        assertThatThrownBy(() -> secrets.put("big", huge))
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("yyyy"));
    }
}
