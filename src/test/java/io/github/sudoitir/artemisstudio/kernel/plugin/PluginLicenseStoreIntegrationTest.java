package io.github.sudoitir.artemisstudio.kernel.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginLicense.LicenseFile;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginLicense.Status;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginLicense.Verdict;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginLicenseStore.State;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.host.PluginRefusedException;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence.PluginInstallEntity;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence.PluginInstallRepository;
import io.github.sudoitir.artemisstudio.kernel.plugin.support.PluginJarBuilder;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * What Studio stores for a plugin's license and what it tells the plugin about it (ADR-0153): the
 * file as opaque bytes, a verdict only for the file it is about, one plugin never reaching another's
 * license, and every change announced.
 */
class PluginLicenseStoreIntegrationTest extends PostgresIntegrationTest {

    private static final byte[] FILE = "license-file-v1".getBytes(StandardCharsets.UTF_8);

    private static final List<PluginLicenseChanged> announced = new CopyOnWriteArrayList<>();

    @Autowired
    PluginLicenseStore store;

    @Autowired
    PluginInstallRepository installs;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JsonMapper json;

    @Autowired
    ConfigurableApplicationContext context;

    private final java.util.List<String> ids = new java.util.ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (String id : ids) {
            jdbc.update("DELETE FROM plugin_license WHERE plugin_id = ?", id);
            jdbc.update("DELETE FROM plugin_install WHERE id = ?", id);
            jdbc.update("DELETE FROM plugin_artifact WHERE sha256 = ?", artifactOf(id));
        }
        announced.clear();
    }

    private static String artifactOf(String id) {
        return PluginLicenseStore.sha256(id.getBytes(StandardCharsets.UTF_8));
    }

    private String plugin(boolean requiresLicense) {
        String id = "acme-lic-" + Long.toString(System.nanoTime(), 36);
        ids.add(id);
        Map<String, Object> descriptor = PluginJarBuilder.defaultDescriptor(id);
        descriptor.put("requiresLicense", requiresLicense);
        jdbc.update(
                "INSERT INTO plugin_artifact (uploaded_at, size_bytes, sha256, content) VALUES (now(), 1, ?, ?)",
                artifactOf(id),
                new byte[] {1});
        installs.saveAndFlush(new PluginInstallEntity(
                id, "1.0.0", "Acme", artifactOf(id), "test", json.writeValueAsString(descriptor)));
        return id;
    }

    private PluginLicense pluginsView(String id) {
        return (PluginLicense) store.beansFor(id).get("pluginLicense");
    }

    @Test
    void aFileIsStoredAsUploadedAndWaitsForItsPluginsVerdict() {
        String id = plugin(true);

        assertThat(store.summary(id).state()).isEqualTo(State.MISSING);
        assertThat(pluginsView(id).file()).isEmpty();

        String sha = store.put(id, FILE, "ops");

        assertThat(sha).isEqualTo(PluginLicenseStore.sha256(FILE));
        LicenseFile file = pluginsView(id).file().orElseThrow();
        assertThat(file.content()).isEqualTo(FILE);
        assertThat(file.sha256()).isEqualTo(sha);
        assertThat(store.summary(id).state()).isEqualTo(State.UNCHECKED);
        assertThat(store.summary(id).uploadedBy()).isEqualTo("ops");
        assertThat(file.toString()).doesNotContain("license-file-v1");
    }

    @Test
    void aVerdictForTheStoredFileIsShownAsReported() {
        String id = plugin(true);
        String sha = store.put(id, FILE, "ops");
        Instant expiry = Instant.now().plus(Duration.ofDays(200));

        pluginsView(id).report(sha, new Verdict(Status.VALID, expiry, "Acme Ltd", "all good"));

        var summary = store.summary(id);
        assertThat(summary.state()).isEqualTo(State.VALID);
        assertThat(summary.licensee()).isEqualTo("Acme Ltd");
        assertThat(summary.detail()).isEqualTo("all good");
        assertThat(summary.expiresAt())
                .isCloseTo(expiry, org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MILLIS));
        assertThat(summary.reportedAt()).isNotNull();

        pluginsView(id).report(sha, new Verdict(Status.OVER_LIMIT, null, "Acme Ltd", "7 of 5"));
        assertThat(store.summary(id).state()).isEqualTo(State.OVER_LIMIT);
        assertThat(store.summary(id).detail()).isEqualTo("7 of 5");
    }

    @Test
    void aValidLicenseExpiringWithinThirtyDaysIsExpiringAndAPastOneIsExpired() {
        String id = plugin(true);
        String sha = store.put(id, FILE, "ops");

        pluginsView(id).report(sha, new Verdict(Status.VALID, Instant.now().plus(Duration.ofDays(29)), null, null));
        assertThat(store.summary(id).state()).isEqualTo(State.EXPIRING);

        pluginsView(id).report(sha, new Verdict(Status.VALID, Instant.now().plus(Duration.ofDays(31)), null, null));
        assertThat(store.summary(id).state()).isEqualTo(State.VALID);

        pluginsView(id).report(sha, new Verdict(Status.VALID, Instant.now().minusSeconds(5), null, null));
        assertThat(store.summary(id).state()).isEqualTo(State.EXPIRED);
    }

    @Test
    void aVerdictForAReplacedFileIsIgnoredAndTheNewFileStaysUnchecked() {
        String id = plugin(true);
        String first = store.put(id, FILE, "ops");
        store.put(id, "license-file-v2".getBytes(StandardCharsets.UTF_8), "ops");

        pluginsView(id).report(first, new Verdict(Status.VALID, null, "Acme Ltd", "late"));

        assertThat(store.summary(id).state()).isEqualTo(State.UNCHECKED);
        assertThat(store.summary(id).licensee()).isNull();
    }

    @Test
    void replacingAFileClearsTheEarlierVerdict() {
        String id = plugin(true);
        String first = store.put(id, FILE, "ops");
        pluginsView(id).report(first, new Verdict(Status.VALID, null, "Acme Ltd", null));

        store.put(id, "license-file-v2".getBytes(StandardCharsets.UTF_8), "ops");

        assertThat(store.summary(id).state()).isEqualTo(State.UNCHECKED);
        assertThat(store.summary(id).licensee()).isNull();
        assertThat(store.summary(id).reportedAt()).isNull();
    }

    @Test
    void aLongLicenseeAndDetailAreCut() {
        String id = plugin(true);
        String sha = store.put(id, FILE, "ops");

        pluginsView(id).report(sha, new Verdict(Status.INVALID, null, "L".repeat(300), "D".repeat(700)));

        assertThat(store.summary(id).licensee()).hasSize(200);
        assertThat(store.summary(id).detail()).hasSize(500);
    }

    @Test
    void aPluginNeverSeesOrJudgesAnotherPluginsLicense() {
        String mine = plugin(true);
        String theirs = plugin(true);
        String sha = store.put(mine, FILE, "ops");

        assertThat(pluginsView(theirs).file()).isEmpty();
        pluginsView(theirs).report(sha, new Verdict(Status.VALID, null, "Intruder", null));

        assertThat(store.summary(mine).state()).isEqualTo(State.UNCHECKED);
        assertThat(store.summary(theirs).state()).isEqualTo(State.MISSING);
        assertThat(pluginsView(mine).file()).isPresent();
    }

    @Test
    void aPluginThatDoesNotNeedALicenseCannotHaveOne() {
        String id = plugin(false);

        assertThatThrownBy(() -> store.put(id, FILE, "ops"))
                .isInstanceOfSatisfying(
                        PluginRefusedException.class,
                        e -> assertThat(e.violations())
                                .singleElement()
                                .satisfies(v -> assertThat(v.code()).isEqualTo("license-not-required")));
        assertThatThrownBy(() -> store.put("acme-nobody", FILE, "ops"))
                .isInstanceOfSatisfying(
                        PluginRefusedException.class,
                        e -> assertThat(e.violations())
                                .singleElement()
                                .satisfies(v -> assertThat(v.code()).isEqualTo("not-found")));
    }

    @Test
    void anEmptyOrOversizedFileIsRefusedWithoutItsContentInTheMessage() {
        String id = plugin(true);
        byte[] oversized = new byte[PluginLicenseStore.MAX_BYTES + 1];
        java.util.Arrays.fill(oversized, (byte) 'x');

        assertThatThrownBy(() -> store.put(id, new byte[0], "ops"))
                .isInstanceOf(PluginRefusedException.class)
                .hasMessageNotContaining("xxxx");
        assertThatThrownBy(() -> store.put(id, oversized, "ops"))
                .isInstanceOf(PluginRefusedException.class)
                .hasMessageNotContaining("xxxx");
        assertThat(store.put(id, new byte[PluginLicenseStore.MAX_BYTES], "ops")).isNotBlank();
        assertThat(store.summary(id).state()).isEqualTo(State.UNCHECKED);
    }

    @Test
    void removingTheFileMakesItMissingAndRemovingNothingIsNotFound() {
        String id = plugin(true);
        store.put(id, FILE, "ops");

        store.remove(id);

        assertThat(pluginsView(id).file()).isEmpty();
        assertThat(store.summary(id).state()).isEqualTo(State.MISSING);
        assertThatThrownBy(() -> store.remove(id)).isInstanceOf(PluginRefusedException.class);
    }

    @Test
    void uninstallingKeepsTheFileAndPurgingDeletesIt() {
        String id = plugin(true);
        store.put(id, FILE, "ops");

        jdbc.update("UPDATE plugin_install SET status = 'uninstalled' WHERE id = ?", id);
        assertThat(pluginsView(id).file()).as("kept on uninstall").isPresent();

        store.onPurged(new PluginPurged(id));
        assertThat(pluginsView(id).file()).as("deleted on purge").isEmpty();
    }

    @Test
    void everyChangeIsAnnouncedToThePluginsOnEveryReplica() {
        context.addApplicationListener((ApplicationListener<PayloadApplicationEvent<?>>) event -> {
            if (event.getPayload() instanceof PluginLicenseChanged changed) {
                announced.add(changed);
            }
        });
        String id = plugin(true);

        store.put(id, FILE, "ops");
        await().atMost(Duration.ofSeconds(5)).until(() -> announced.contains(new PluginLicenseChanged(id)));
        announced.clear();

        store.remove(id);
        await().atMost(Duration.ofSeconds(5)).until(() -> announced.contains(new PluginLicenseChanged(id)));
    }

    @Test
    void healthNamesTheRunningPluginsThatNeedAttention() {
        String missing = plugin(true);
        String fine = plugin(true);
        plugin(false);
        jdbc.update("UPDATE plugin_install SET status = 'active' WHERE id IN (?, ?)", missing, fine);
        String sha = store.put(fine, FILE, "ops");
        pluginsView(fine).report(sha, new Verdict(Status.VALID, Instant.now().plus(Duration.ofDays(90)), null, null));

        var declaring = store.activeDeclaring();

        assertThat(declaring.get(missing).state()).isEqualTo(State.MISSING);
        assertThat(declaring.get(fine).state()).isEqualTo(State.VALID);
        assertThat(declaring).doesNotContainKey("acme-lic-none");
    }
}
