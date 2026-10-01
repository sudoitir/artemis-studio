package io.github.sudoitir.artemisstudio.kernel.plugin.internal.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.github.sudoitir.artemisstudio.kernel.core.StudioHealth;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginInstallStatus;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence.PluginArtifactRepository;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence.PluginInstallRepository;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.runtime.PluginRuntimeRegistry;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.runtime.PluginRuntimeRegistry.Active;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.store.PluginStore;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.trust.PluginTrust;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.trust.PluginTrustHealthIndicator;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.trust.TrustDecision.Status;
import io.github.sudoitir.artemisstudio.kernel.plugin.support.PluginJarBuilder;
import io.github.sudoitir.artemisstudio.kernel.plugin.support.SignInPlugin;
import io.github.sudoitir.artemisstudio.kernel.plugin.support.TestSigningKeys;
import io.github.sudoitir.artemisstudio.kernel.plugin.support.TrustedTestKey;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The trust gate in {@link PluginHost} (design.md §5) against the real context and Postgres: the plan
 * reports who signed a jar and never refuses for it, and activation, rollback and enable refuse an
 * untrusted or unsigned jar, or one that needs an acknowledgement, on the server.
 */
class PluginTrustGateIntegrationTest extends PostgresIntegrationTest {

    private static final String PUBLISHER = TestSigningKeys.PUBLISHER.fingerprint();
    private static final String OTHER = TestSigningKeys.OTHER.fingerprint();

    @Autowired
    PluginHost host;

    @Autowired
    PluginStore store;

    @Autowired
    PluginTrust trust;

    @Autowired
    PluginTrustHealthIndicator health;

    @Autowired
    PluginRuntimeRegistry registry;

    @Autowired
    PluginInstallRepository installs;

    @Autowired
    PluginArtifactRepository artifacts;

    @Autowired
    JdbcTemplate jdbc;

    @Value("${management.endpoint.health.group.studio.include}")
    String studioGroup;

    private final List<String> pluginIds = new ArrayList<>();
    private final List<String> shas = new ArrayList<>();

    @BeforeEach
    void trustThePublisher() {
        jdbc.update("DELETE FROM plugin_trusted_key");
        trust.setAllowUnverified(false, "test");
        TrustedTestKey.trust(jdbc);
    }

    @AfterEach
    void cleanUp() {
        for (String id : pluginIds) {
            registry.get(id).ifPresent(slot -> {
                if (slot instanceof Active active) {
                    active.runtime().close();
                }
            });
            registry.remove(id);
            installs.deleteById(id);
        }
        pluginIds.clear();
        for (String sha : shas) {
            jdbc.update("DELETE FROM plugin_upload WHERE sha256 = ?", sha);
            artifacts.deleteById(sha);
        }
        shas.clear();
        jdbc.update("DELETE FROM plugin_trusted_key");
        trust.setAllowUnverified(false, "test");
    }

    private static String uniqueId(String prefix) {
        return prefix + "-" + Long.toString(System.nanoTime(), 36);
    }

    private PluginJarBuilder plugin(String id, String version) {
        pluginIds.add(id);
        return new PluginJarBuilder(id).descriptorField("version", version);
    }

    private String upload(PluginJarBuilder builder) throws Exception {
        String sha = store.put(Files.readAllBytes(builder.build()));
        shas.add(sha);
        return sha;
    }

    private void awaitActive(String id, String sha) {
        await("plugin '" + id + "' becomes active on " + sha)
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> {
                    var entity = installs.findById(id);
                    if (entity.isPresent() && entity.get().status() == PluginInstallStatus.FAILED) {
                        throw new AssertionError(
                                "Plugin '" + id + "' failed: " + entity.get().getFailure());
                    }
                    return entity.isPresent()
                            && entity.get().status() == PluginInstallStatus.ACTIVE
                            && entity.get().getSha256().equals(sha);
                });
    }

    private static void assertRefused(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        PluginRefusedException.class,
                        e -> assertThat(e.violations()).extracting("code").containsExactly(code));
    }

    @Test
    void aTrustedInstallActivatesAndRecordsItsSigner() throws Exception {
        String id = uniqueId("gate-trusted");
        String sha = upload(plugin(id, "1.0.0"));

        ActivationPlan plan = host.plan(sha);
        assertThat(plan.trust().status()).isEqualTo(Status.TRUSTED);
        assertThat(plan.trust().fingerprint()).isEqualTo(PUBLISHER);
        assertThat(plan.trust().keyName()).isEqualTo("Test publisher");
        assertThat(plan.trust().allowed()).isTrue();
        assertThat(plan.acknowledgements()).isEmpty();

        host.activate(sha, "tester", false);
        awaitActive(id, sha);

        var summary = host.status(id).orElseThrow();
        assertThat(summary.signerFingerprint()).isEqualTo(PUBLISHER);
        assertThat(summary.signerSubject()).contains("Acme Test Publisher");
        assertThat(summary.verified()).isTrue();
    }

    @Test
    void anUnsignedJarIsRefusedByDefaultAndItsUploadStaysPending() throws Exception {
        String id = uniqueId("gate-unsigned");
        var inspection = host.inspect(plugin(id, "1.0.0").unsigned().build(), "tester");

        assertThat(inspection.plan().trust().status()).isEqualTo(Status.UNSIGNED);
        assertThat(inspection.plan().trust().allowed()).isFalse();
        shas.add(inspection.sha256());
        assertThat(host.isPendingUpload(inspection.sha256())).isTrue();
        assertRefused(() -> host.activateUpload(inspection.sha256(), "tester", true), "plugin-unsigned");
        assertThat(host.isPendingUpload(inspection.sha256())).isTrue();
        assertThat(installs.findById(id)).isEmpty();
    }

    @Test
    void aJarSignedByAnUntrustedKeyIsRefusedAndThePlanNamesTheKey() throws Exception {
        String id = uniqueId("gate-untrusted");
        var inspection = host.inspect(
                plugin(id, "1.0.0").signedBy(TestSigningKeys.OTHER_RESOURCE).build(), "tester");
        shas.add(inspection.sha256());

        assertThat(inspection.plan().trust().status()).isEqualTo(Status.UNTRUSTED);
        assertThat(inspection.plan().trust().fingerprint()).isEqualTo(OTHER);
        assertThat(inspection.plan().trust().subject()).contains("CN=");
        assertThat(host.isPendingUpload(inspection.sha256())).isTrue();
        assertRefused(() -> host.activateUpload(inspection.sha256(), "tester", true), "plugin-untrusted");
    }

    @Test
    void withTheAllowanceOnAnUnsignedJarNeedsAcknowledgement() throws Exception {
        trust.setAllowUnverified(true, "test");
        String id = uniqueId("gate-allowed");
        String sha = upload(plugin(id, "1.0.0").unsigned());

        ActivationPlan plan = host.plan(sha);
        assertThat(plan.trust().allowed()).isTrue();
        assertThat(plan.acknowledgements()).containsExactly("unverified");
        assertRefused(() -> host.activate(sha, "tester", false), "acknowledgement-required");
        assertThat(installs.findById(id)).isEmpty();

        host.activate(sha, "tester", true);
        awaitActive(id, sha);

        var summary = host.status(id).orElseThrow();
        assertThat(summary.signerFingerprint()).isNull();
        assertThat(summary.verified()).isFalse();
    }

    @Test
    void aKeyRemovedAfterThePlanRefusesActivation() throws Exception {
        String id = uniqueId("gate-removed");
        String sha = upload(plugin(id, "1.0.0"));
        assertThat(host.plan(sha).trust().allowed()).isTrue();

        trust.remove(PUBLISHER);

        assertRefused(() -> host.activate(sha, "tester", true), "plugin-untrusted");
        assertThat(installs.findById(id)).isEmpty();
    }

    @Test
    void anUpdateThatAddsAPermissionNeedsAcknowledgement() throws Exception {
        String id = uniqueId("gate-perm");
        String shaV1 = upload(plugin(id, "1.0.0"));
        host.activate(shaV1, "tester", false);
        awaitActive(id, shaV1);

        String shaV2 =
                upload(plugin(id, "2.0.0").descriptorField("permissions", List.of(Map.of("action", id + ":read"))));
        assertThat(host.plan(shaV2).acknowledgements()).containsExactly("permissions-added");
        assertRefused(() -> host.activate(shaV2, "tester", false), "acknowledgement-required");

        host.activate(shaV2, "tester", true);
        awaitActive(id, shaV2);
    }

    @Test
    void anUpdateSignedByAnotherTrustedKeyNeedsAcknowledgementAndRecordsTheNewSigner() throws Exception {
        TrustedTestKey.trust(jdbc, TestSigningKeys.OTHER, "Other publisher");
        String id = uniqueId("gate-signer");
        String shaV1 = upload(plugin(id, "1.0.0"));
        host.activate(shaV1, "tester", false);
        awaitActive(id, shaV1);

        String shaV2 = upload(plugin(id, "2.0.0").signedBy(TestSigningKeys.OTHER_RESOURCE));
        PlanTrust planTrust = host.plan(shaV2).trust();
        assertThat(planTrust.signerChanged()).isTrue();
        assertThat(planTrust.previousFingerprint()).isEqualTo(PUBLISHER);
        assertThat(planTrust.fingerprint()).isEqualTo(OTHER);
        assertThat(host.plan(shaV2).acknowledgements()).containsExactly("signer-changed");
        assertRefused(() -> host.activate(shaV2, "tester", false), "acknowledgement-required");

        host.activate(shaV2, "tester", true);
        awaitActive(id, shaV2);
        assertThat(host.status(id).orElseThrow().signerFingerprint()).isEqualTo(OTHER);

        // The signer recorded is the one of the sha actually activated: rolling back restores it.
        host.rollback(id, "tester", true);
        awaitActive(id, shaV1);
        assertThat(host.status(id).orElseThrow().signerFingerprint()).isEqualTo(PUBLISHER);
    }

    @Test
    void aRollbackToAnUnsignedVersionIsRefusedWhileTheAllowanceIsOff() throws Exception {
        trust.setAllowUnverified(true, "test");
        String id = uniqueId("gate-rollback");
        String shaV1 = upload(plugin(id, "1.0.0").unsigned());
        host.activate(shaV1, "tester", true);
        awaitActive(id, shaV1);
        String shaV2 = upload(plugin(id, "2.0.0"));
        host.activate(shaV2, "tester", true);
        awaitActive(id, shaV2);

        trust.setAllowUnverified(false, "test");

        assertRefused(() -> host.rollback(id, "tester", true), "plugin-unsigned");
        assertThat(installs.findById(id).orElseThrow().getSha256()).isEqualTo(shaV2);
    }

    @Test
    void enablingAPluginWhoseKeyWasRemovedIsRefused() throws Exception {
        String id = uniqueId("gate-enable");
        String sha = upload(plugin(id, "1.0.0"));
        host.activate(sha, "tester", false);
        awaitActive(id, sha);
        host.disable(id, false, "tester");
        trust.remove(PUBLISHER);

        assertRefused(() -> host.enable(id, "tester", true), "plugin-untrusted");
    }

    @Test
    void removingTheKeyMarksTheInstalledPluginUnverifiedAndDegradesHealth() throws Exception {
        String id = uniqueId("gate-health");
        String sha = upload(plugin(id, "1.0.0"));
        host.activate(sha, "tester", false);
        awaitActive(id, sha);
        assertThat(host.status(id).orElseThrow().verified()).isTrue();
        assertThat(health.health().getDetails().get("unverified")).asList().doesNotContain(id);

        trust.remove(PUBLISHER);

        assertThat(host.status(id).orElseThrow().verified()).isFalse();
        var report = health.health();
        assertThat(report.getStatus()).isEqualTo(StudioHealth.DEGRADED);
        assertThat(report.getDetails().get("unverified")).asList().contains(id);
    }

    @Test
    void anUnsignedSignInPluginIsRefusedEvenWithTheAllowanceOn() throws Exception {
        trust.setAllowUnverified(true, "test");
        String id = uniqueId("gate-signin-unsigned");
        pluginIds.add(id);
        var inspection = host.inspect(SignInPlugin.jar(id, false).unsigned().build(), "tester");
        shas.add(inspection.sha256());

        assertThat(inspection.plan().trust().allowed()).isFalse();
        assertThatThrownBy(() -> host.activateUpload(inspection.sha256(), "tester", true))
                .isInstanceOfSatisfying(PluginRefusedException.class, e -> {
                    assertThat(e.violations()).extracting("code").containsExactly("plugin-signin-unverified");
                    assertThat(e.violations().getFirst().message()).contains(id + ":corp");
                });
        assertThat(installs.findById(id)).isEmpty();
    }

    @Test
    void aTrustedSignInPluginNeedsConfirmationAndThenActivates() throws Exception {
        String id = uniqueId("gate-signin");
        pluginIds.add(id);
        String sha = upload(SignInPlugin.jar(id, false));

        ActivationPlan plan = host.plan(sha);
        assertThat(plan.trust().allowed()).isTrue();
        assertThat(plan.diff().identityProvidersAdded()).containsExactly(id + ":corp");
        assertThat(plan.acknowledgements()).containsExactly("signin-added");
        assertRefused(() -> host.activate(sha, "tester", false), "acknowledgement-required");
        assertThat(installs.findById(id)).isEmpty();

        host.activate(sha, "tester", true);
        awaitActive(id, sha);
    }

    @Test
    void anUpdateThatKeepsTheSameProviderNeedsNoNewConfirmation() throws Exception {
        String id = uniqueId("gate-signin-keep");
        pluginIds.add(id);
        String shaV1 = upload(SignInPlugin.jar(id, false));
        host.activate(shaV1, "tester", true);
        awaitActive(id, shaV1);

        String shaV2 = upload(SignInPlugin.jar(id, false).descriptorField("version", "2.0.0"));

        assertThat(host.plan(shaV2).acknowledgements()).isEmpty();
    }

    @Test
    void theStudioHealthGroupIncludesThePluginTrustContributor() {
        assertThat(studioGroup.split(",")).contains("pluginTrust");
    }
}
