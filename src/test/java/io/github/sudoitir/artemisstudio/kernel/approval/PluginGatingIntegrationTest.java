package io.github.sudoitir.artemisstudio.kernel.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.sudoitir.artemisstudio.feature.plugins.PluginAdministration;
import io.github.sudoitir.artemisstudio.kernel.gate.HeldState;
import io.github.sudoitir.artemisstudio.kernel.gate.Trait;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginInstallStatus;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.host.PluginHost;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence.PluginArtifactRepository;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence.PluginInstallEntity;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.runtime.PluginRuntimeRegistry;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.runtime.PluginRuntimeRegistry.Active;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.trust.PluginTrust;
import io.github.sudoitir.artemisstudio.kernel.plugin.support.PluginJarBuilder;
import io.github.sudoitir.artemisstudio.kernel.plugin.support.TestSigningKeys;
import io.github.sudoitir.artemisstudio.kernel.plugin.support.TrustedTestKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Plugin administration behind the approval gate (ADR-0179, threat model rows 16 to 18): the lifecycle, license,
 * installer and trust actions are held while a provider holds. Changes that could remove or replace the provider, and
 * every installer, trusted key and trust policy change, carry GATE_INTEGRITY.
 */
class PluginGatingIntegrationTest extends GatedAccessTestBase {

    @Autowired
    PluginAdministration administration;

    @Autowired
    PluginHost host;

    @Autowired
    PluginTrust trust;

    @Autowired
    PluginRuntimeRegistry registry;

    @Autowired
    PluginArtifactRepository artifacts;

    private final List<String> pluginIds = new ArrayList<>();
    private final List<String> shas = new ArrayList<>();

    private Person alice;
    private Person bob;

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
        jdbc.update("DELETE FROM plugin_installer WHERE granted_by = 'tester'");
        jdbc.update("DELETE FROM plugin_trusted_key");
        trust.setAllowUnverified(false, "test");
    }

    private void setUp() {
        alice = requester();
        bob = approver();
        approversAre(bob);
        signIn(alice);
        arm();
    }

    private String plugin(String prefix) {
        String id = prefix + "-" + Long.toString(System.nanoTime(), 36);
        pluginIds.add(id);
        return id;
    }

    private String upload(PluginJarBuilder builder) throws Exception {
        String sha = host.inspect(builder.build(), "tester").sha256();
        shas.add(sha);
        return sha;
    }

    /**
     * An installed plugin that is not the provider and needs a license: only its row matters to a request that is
     * held, and to a license file it is given.
     */
    private String installedPlugin() {
        String id = plugin("gate-other");
        String sha = pluginStore.put(("gate-other-" + UUID.randomUUID()).getBytes());
        installs.save(new PluginInstallEntity(
                id, "1.0.0", "Acme", sha, "tester", storedDescriptor(id, Map.of("requiresLicense", true))));
        jdbc.update("UPDATE plugin_install SET status = 'active' WHERE id = ?", id);
        return id;
    }

    // ---- activating an upload -------------------------------------------------------------------------

    @Test
    void anUploadIsHeldBySha256AndTheApprovedReplayActivatesTheSameJar() throws Exception {
        String id = plugin("gate-upload");
        String sha = upload(new PluginJarBuilder(id).descriptorField("version", "1.0.0"));
        setUp();

        UUID held = hold(alice, () -> administration.activate(sha, false));

        assertThat(installs.findById(id)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT params::text FROM held_operation WHERE id = ?", String.class, held))
                .contains(sha);
        assertTraits("plugin.activate-upload", Trait.ACCESS_CONTROL);
        approve(bob, held);
        awaitState(held, HeldState.SUCCEEDED);
        await("the plugin '" + id + "' becomes active on " + sha)
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> installs.findById(id)
                        .filter(e -> e.status() == PluginInstallStatus.ACTIVE
                                && e.getSha256().equals(sha))
                        .isPresent());
    }

    @Test
    void anUploadWaitingForApprovalOutlivesItsDayAndIsReleasedWhenTheRequestEnds() throws Exception {
        String id = plugin("gate-aged");
        String sha = upload(new PluginJarBuilder(id).descriptorField("version", "1.0.0"));
        String unrelated = upload(new PluginJarBuilder(plugin("gate-stale")).descriptorField("version", "1.0.0"));
        setUp();
        UUID held = hold(alice, () -> administration.activate(sha, false));
        jdbc.update("UPDATE plugin_upload SET uploaded_at = now() - interval '3 days'");

        // Any new upload sweeps the expired ones.
        upload(new PluginJarBuilder(plugin("gate-sweeper")).descriptorField("version", "1.0.0"));

        assertThat(host.isPendingUpload(sha)).as("held for approval").isTrue();
        assertThat(host.isPendingUpload(unrelated)).as("not referenced").isFalse();
        approve(bob, held);
        awaitState(held, HeldState.SUCCEEDED);
        await("the plugin '" + id + "' becomes active")
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> installs.findById(id)
                        .filter(e -> e.status() == PluginInstallStatus.ACTIVE)
                        .isPresent());
    }

    @Test
    void aReplayWhoseUploadIsGoneIsRefusedAndInstallsNothing() throws Exception {
        String id = plugin("gate-gone");
        String sha = upload(new PluginJarBuilder(id).descriptorField("version", "1.0.0"));
        setUp();
        UUID held = hold(alice, () -> administration.activate(sha, false));
        host.forgetUpload(sha);

        approve(bob, held);

        awaitState(held, HeldState.REFUSED);
        assertThat(installs.findById(id)).isEmpty();
    }

    @Test
    void anUploadThatDeclaresAnApprovalProviderCarriesGateIntegrity() {
        String sha = pluginStore.put(("gate-declares-" + UUID.randomUUID()).getBytes());
        shas.add(sha);
        jdbc.update(
                "INSERT INTO plugin_upload (uploaded_at, sha256, plugin_id, uploaded_by, descriptor, report)"
                        + " VALUES (now(), ?, ?, 'tester', ?::jsonb, '{}'::jsonb)",
                sha,
                plugin("gate-declares"),
                "{\"approvalProvider\":{\"approverPermission\":\"x:approve\"}}");
        setUp();

        hold(alice, () -> administration.activate(sha, false));

        assertTraits("plugin.activate-upload", Trait.ACCESS_CONTROL, Trait.GATE_INTEGRITY);
    }

    // ---- the provider itself ----------------------------------------------------------------------------

    @Test
    void disablingTheProviderPluginCarriesGateIntegrityAndAnotherPluginDoesNot() {
        setUp();
        String other = installedPlugin();

        hold(alice, () -> administration.disable(other, false));
        assertTraits("plugin.disable", Trait.ACCESS_CONTROL);
        assertThat(installs.findById(other).orElseThrow().status()).isEqualTo(PluginInstallStatus.ACTIVE);

        hold(alice, () -> administration.disable(PROVIDER, false));
        assertTraits("plugin.disable", Trait.ACCESS_CONTROL, Trait.GATE_INTEGRITY);
        assertThat(installs.findById(PROVIDER).orElseThrow().status()).isEqualTo(PluginInstallStatus.ACTIVE);

        hold(alice, () -> administration.uninstall(PROVIDER, false));
        assertTraits("plugin.uninstall", Trait.ACCESS_CONTROL, Trait.GATE_INTEGRITY);
        hold(alice, () -> administration.enable(PROVIDER, false));
        assertTraits("plugin.enable", Trait.ACCESS_CONTROL, Trait.GATE_INTEGRITY);
        hold(alice, () -> administration.rollback(PROVIDER, false));
        assertTraits("plugin.rollback", Trait.ACCESS_CONTROL, Trait.GATE_INTEGRITY);
        hold(alice, () -> administration.purge(PROVIDER, false));
        assertTraits("plugin.purge", Trait.ACCESS_CONTROL, Trait.DESTRUCTIVE, Trait.GATE_INTEGRITY);
        assertThat(administration.purge(other, true)).isNotNull();
    }

    @Test
    void disablingWithItsDependentsCouldReachTheProviderSoItCarriesGateIntegrity() {
        setUp();
        String other = installedPlugin();

        hold(alice, () -> administration.disable(other, true));

        assertTraits("plugin.disable", Trait.ACCESS_CONTROL, Trait.GATE_INTEGRITY);
    }

    // ---- installers, trusted keys and the trust policy ------------------------------------------------------

    @Test
    void grantingAndRevokingAnInstallerAreHeldThenRun() {
        setUp();
        Person newcomer = newUser();

        UUID grant = hold(alice, () -> administration.grantInstaller(newcomer.username()));
        assertThat(installers.isInstaller(newcomer.id())).isFalse();
        assertTraits("plugin.installer.add", Trait.ACCESS_CONTROL, Trait.GATE_INTEGRITY);
        approve(bob, grant);
        awaitState(grant, HeldState.SUCCEEDED);
        assertThat(installers.isInstaller(newcomer.id())).isTrue();

        UUID revoke = hold(alice, () -> administration.revokeInstaller(newcomer.id()));
        assertThat(installers.isInstaller(newcomer.id())).isTrue();
        assertTraits("plugin.installer.remove", Trait.ACCESS_CONTROL, Trait.GATE_INTEGRITY);
        signIn(bob);
        installers.grant(bob.id(), "tester");
        approve(bob, revoke);
        awaitState(revoke, HeldState.SUCCEEDED);
        assertThat(installers.isInstaller(newcomer.id())).isFalse();
    }

    @Test
    void trustingAndUntrustingAKeyAndChangingThePolicyAreHeldAndTheStepUpIsAskedOnce() throws Exception {
        String other = plugin("gate-key");
        String sha = upload(new PluginJarBuilder(other).signedBy(TestSigningKeys.OTHER_RESOURCE));
        setUp();
        AtomicInteger stepUps = new AtomicInteger();
        String otherKey = TestSigningKeys.OTHER.fingerprint();

        UUID add = hold(alice, () -> administration.addKey("Other publisher", sha, null, stepUps::incrementAndGet));
        assertThat(stepUps).as("the step-up is asked when the request is made").hasValue(1);
        assertTraits("plugin.trust-key.add", Trait.ACCESS_CONTROL, Trait.GATE_INTEGRITY);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM plugin_trusted_key WHERE fingerprint = ?", Long.class, otherKey))
                .isZero();
        approve(bob, add);
        awaitState(add, HeldState.SUCCEEDED);
        assertThat(stepUps).as("a replay asks for no step-up").hasValue(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM plugin_trusted_key WHERE fingerprint = ?", Long.class, otherKey))
                .isEqualTo(1);

        UUID remove = hold(alice, () -> administration.removeKey(otherKey, () -> {}));
        assertTraits("plugin.trust-key.remove", Trait.ACCESS_CONTROL, Trait.GATE_INTEGRITY);
        approve(bob, remove);
        awaitState(remove, HeldState.SUCCEEDED);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM plugin_trusted_key WHERE fingerprint = ?", Long.class, otherKey))
                .isZero();

        UUID policy = hold(alice, () -> administration.setAllowUnverified(true, () -> {}));
        assertThat(trust.allowUnverified()).isFalse();
        assertTraits("plugin.trust-policy.set", Trait.ACCESS_CONTROL, Trait.GATE_INTEGRITY);
        approve(bob, policy);
        awaitState(policy, HeldState.SUCCEEDED);
        assertThat(trust.allowUnverified()).isTrue();
    }

    @Test
    void aRefusedStepUpIsAuditedAndHoldsNothing() {
        setUp();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> administration.setAllowUnverified(true, () -> {
                    throw new io.github.sudoitir.artemisstudio.kernel.security.ReauthenticationRequiredException();
                }))
                .isInstanceOf(io.github.sudoitir.artemisstudio.kernel.security.ReauthenticationRequiredException.class);

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM held_operation WHERE requester_id = ?", Long.class, alice.id()))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM audit_event WHERE action = 'PLUGIN_TRUST_POLICY' AND outcome <> 'SUCCESS'",
                        Long.class))
                .isGreaterThanOrEqualTo(1);
    }

    // ---- license files ---------------------------------------------------------------------------------------

    @Test
    void aLicenseFileIsHeldAndNeverStoredOrShownInTheClear() {
        setUp();
        String id = installedPlugin();
        String content = "license-secret-" + UUID.randomUUID();

        UUID held = hold(alice, () -> administration.uploadLicense(id, content.getBytes(), () -> {}));

        assertTraits("plugin.license.put", Trait.ACCESS_CONTROL);
        String params = jdbc.queryForObject("SELECT params::text FROM held_operation WHERE id = ?", String.class, held);
        assertThat(params).contains("[redacted]").doesNotContain(content);
        assertThat(lastRequest("plugin.license.put").params()).doesNotContain(content);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM plugin_license WHERE plugin_id = ?", Long.class, id))
                .isZero();

        hold(alice, () -> administration.removeLicense(id, () -> {}));
        assertTraits("plugin.license.delete", Trait.ACCESS_CONTROL);
    }

    @Test
    void anApprovedLicenseFileIsStoredOnceAndAnApprovedRemovalRemovesIt() {
        setUp();
        String id = installedPlugin();
        byte[] content = ("license-" + UUID.randomUUID()).getBytes();

        UUID put = hold(alice, () -> administration.uploadLicense(id, content, () -> {}));
        approve(bob, put);
        awaitState(put, HeldState.SUCCEEDED);

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM plugin_license WHERE plugin_id = ? AND size = ?",
                        Long.class,
                        id,
                        content.length))
                .isOne();
        assertThat(ran("PLUGIN_LICENSE_UPLOAD", id)).isOne();

        UUID delete = hold(alice, () -> administration.removeLicense(id, () -> {}));
        approve(bob, delete);
        awaitState(delete, HeldState.SUCCEEDED);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM plugin_license WHERE plugin_id = ?", Long.class, id))
                .isZero();
        assertThat(ran("PLUGIN_LICENSE_REMOVE", id)).isOne();
    }

    // ---- lifecycle replays ---------------------------------------------------------------------------

    @Test
    void anApprovedEnableStartsTheDisabledPluginOnce() throws Exception {
        String id = plugin("gate-enable");
        people();
        String sha = activated(id, "1.0.0");
        administration.disable(id, false);
        awaitStatus(id, PluginInstallStatus.DISABLED);
        arm();

        UUID held = hold(alice, () -> administration.enable(id, false));
        assertThat(installs.findById(id).orElseThrow().status()).isEqualTo(PluginInstallStatus.DISABLED);
        approve(bob, held);

        awaitState(held, HeldState.SUCCEEDED);
        awaitStatus(id, PluginInstallStatus.ACTIVE);
        assertThat(installs.findById(id).orElseThrow().getSha256()).isEqualTo(sha);
        assertThat(ran("PLUGIN_ENABLE", id)).isOne();
    }

    @Test
    void anApprovedRollbackReturnsToThePreviousVersionOnce() throws Exception {
        String id = plugin("gate-rollback");
        people();
        String first = activated(id, "1.0.0");
        String second = activated(id, "1.1.0");
        arm();

        UUID held = hold(alice, () -> administration.rollback(id, false));
        assertThat(installs.findById(id).orElseThrow().getSha256()).isEqualTo(second);
        approve(bob, held);

        awaitState(held, HeldState.SUCCEEDED);
        await("the plugin '" + id + "' runs " + first + " again")
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> installs.findById(id)
                        .filter(e -> e.status() == PluginInstallStatus.ACTIVE
                                && e.getSha256().equals(first))
                        .isPresent());
        assertThat(ran("PLUGIN_ROLLBACK", id)).isOne();
    }

    @Test
    void anApprovedUninstallThenAnApprovedPurgeEachRunOnce() throws Exception {
        String id = plugin("gate-remove");
        people();
        activated(id, "1.0.0");
        arm();

        UUID uninstall = hold(alice, () -> administration.uninstall(id, false));
        assertThat(installs.findById(id).orElseThrow().status()).isEqualTo(PluginInstallStatus.ACTIVE);
        approve(bob, uninstall);
        awaitState(uninstall, HeldState.SUCCEEDED);
        awaitStatus(id, PluginInstallStatus.UNINSTALLED);
        assertThat(ran("PLUGIN_UNINSTALL", id)).isOne();

        UUID purge = hold(alice, () -> administration.purge(id, false));
        assertThat(installs.findById(id)).isPresent();
        approve(bob, purge);
        awaitState(purge, HeldState.SUCCEEDED);

        assertThat(installs.findById(id)).isEmpty();
        assertThat(ran("PLUGIN_PURGE", id)).isOne();
    }

    /** The requester and the approver, the requester signed in, with the gate not yet armed. */
    private void people() {
        alice = requester();
        bob = approver();
        approversAre(bob);
        signIn(alice);
    }

    /** Uploads and activates {@code version} of the plugin as the signed-in requester; its jar's sha256. */
    private String activated(String id, String version) throws Exception {
        String sha = upload(new PluginJarBuilder(id).descriptorField("version", version));
        administration.activate(sha, true);
        await("the plugin '" + id + "' becomes active on " + sha)
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> installs.findById(id)
                        .filter(e -> e.status() == PluginInstallStatus.ACTIVE
                                && e.getSha256().equals(sha))
                        .isPresent());
        return sha;
    }

    private void awaitStatus(String id, PluginInstallStatus status) {
        await("the plugin '" + id + "' becomes " + status)
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(50))
                .until(() ->
                        installs.findById(id).filter(e -> e.status() == status).isPresent());
    }

    /** How many times the action ran on the plugin, by its audit rows, whatever came of it. */
    private long ran(String action, String pluginId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE action = ? AND target_name = ?", Long.class, action, pluginId);
    }

    @Test
    void withNoProviderArmedAPluginActionRunsAtOnce() throws Exception {
        alice = requester();
        signIn(alice);
        String id = installedPlugin();

        administration.purge(id, true);
        administration.setAllowUnverified(true, () -> {});

        assertThat(trust.allowUnverified()).isTrue();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM held_operation WHERE requester_id = ?", Long.class, alice.id()))
                .isZero();
    }
}
