package io.github.sudoitir.artemisstudio.kernel.plugin.internal.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginInstallStatus;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.descriptor.PluginDescriptor;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.descriptor.PluginDescriptorParser;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence.PluginArtifactRepository;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence.PluginInstallEntity;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence.PluginInstallRepository;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.runtime.PluginRuntimeRegistry;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.runtime.PluginRuntimeRegistry.Active;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.store.PluginStore;
import io.github.sudoitir.artemisstudio.kernel.plugin.support.PluginJarBuilder;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link PluginHost} against the real application context and a real Postgres (task 6.8, this
 * session's slice): every branch {@code plan}/{@code activate} can take — fresh install Instant,
 * fresh install Brief-maintenance (schema created, contributions diffed), an Instant update, the
 * {@code requires}, downgrade, vendor-mismatch and connection-budget refusals, and the
 * {@code RESTART} activation class.
 */
class PluginHostIT extends PostgresIntegrationTest {

    @Autowired
    PluginHost host;

    @Autowired
    PluginStore store;

    @Autowired
    PluginRuntimeRegistry registry;

    @Autowired
    PluginInstallRepository installs;

    @Autowired
    PluginArtifactRepository artifacts;

    @Autowired
    PluginDescriptorParser descriptorParser;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcTemplate jdbc;

    private final List<String> runtimeIds = new ArrayList<>();
    private final List<String> seededIds = new ArrayList<>();
    private final List<String> uploadedShas = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (String id : runtimeIds) {
            registry.get(id).ifPresent(slot -> {
                if (slot instanceof Active active) {
                    active.runtime().close();
                }
            });
            registry.remove(id);
        }
        runtimeIds.clear();
        for (String id : seededIds) {
            installs.deleteById(id);
        }
        seededIds.clear();
        // plugin_install rows above must be gone first: they FK into plugin_artifact.
        for (String sha : uploadedShas) {
            artifacts.deleteById(sha);
        }
        uploadedShas.clear();
    }

    private static String uniqueId(String prefix) {
        return prefix + "-" + Long.toString(System.nanoTime(), 36);
    }

    private PluginJarBuilder emptyPlugin(String id) {
        String pkg = "com.acme." + id.replace('-', '_');
        return new PluginJarBuilder(id)
                .descriptorField("basePackage", pkg)
                .descriptorField("configuration", pkg + ".PluginConfig")
                .changelog("""
                        <?xml version="1.0" encoding="UTF-8"?>
                        <databaseChangeLog
                                xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                                    http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
                        </databaseChangeLog>
                        """);
    }

    private PluginJarBuilder pluginWithATable(String id) {
        return new PluginJarBuilder(id)
                .descriptorField("basePackage", "com.acme." + id.replace('-', '_'))
                .descriptorField("configuration", "com.acme." + id.replace('-', '_') + ".PluginConfig")
                .changelog("""
                        <?xml version="1.0" encoding="UTF-8"?>
                        <databaseChangeLog
                                xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                                    http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
                            <include file="changes/0001.sql" relativeToChangelogFile="true"/>
                        </databaseChangeLog>
                        """)
                .entry("db/changelog/plugin/" + id + "/changes/0001.sql", """
                        --liquibase formatted sql

                        --changeset acme:0001
                        CREATE TABLE thing (id uuid NOT NULL PRIMARY KEY);
                        --rollback DROP TABLE thing;
                        """);
    }

    private String upload(PluginJarBuilder builder) throws Exception {
        Path jar = builder.build();
        String sha = store.put(Files.readAllBytes(jar));
        uploadedShas.add(sha);
        return sha;
    }

    private PluginDescriptor descriptorOf(String sha256) throws Exception {
        try (JarFile jarFile = new JarFile(store.materialize(sha256).toFile())) {
            byte[] bytes = jarFile.getInputStream(jarFile.getEntry("META-INF/artemis-studio/plugin.json"))
                    .readAllBytes();
            return descriptorParser.parse(bytes);
        }
    }

    private PluginSummary awaitStatus(String id, PluginInstallStatus want) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(deadline)) {
            var summary = host.status(id);
            if (summary.isPresent() && summary.get().status() == want) {
                return summary.get();
            }
            if (summary.isPresent()
                    && summary.get().status() == PluginInstallStatus.FAILED
                    && want != PluginInstallStatus.FAILED) {
                throw new AssertionError("Plugin '" + id + "' failed while waiting for " + want + ": "
                        + summary.get().failure());
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Plugin '" + id + "' never reached " + want + "; last status: " + host.status(id));
    }

    @Test
    void freshInstallWithNoChangesetsIsInstantAndActivatesImmediately() throws Exception {
        String id = uniqueId("acme-empty");
        String sha = upload(emptyPlugin(id));

        ActivationPlan plan = host.plan(sha);
        assertThat(plan.pluginId()).isEqualTo(id);
        assertThat(plan.fromVersion()).isNull();
        assertThat(plan.activationClass()).isEqualTo(ActivationClass.INSTANT);
        assertThat(plan.pendingChangesets()).isEmpty();
        assertThat(plan.missingRequires()).isEmpty();
        assertThat(plan.compatible()).isTrue();

        host.activate(sha, "tester");
        runtimeIds.add(id);
        seededIds.add(id);

        PluginSummary active = awaitStatus(id, PluginInstallStatus.ACTIVE);
        assertThat(active.sha256()).isEqualTo(sha);
        assertThat(registry.get(id)).containsInstanceOf(Active.class);
    }

    @Test
    void freshInstallWithPendingChangesetsIsBriefMaintenanceAndCreatesTheSchema() throws Exception {
        String id = uniqueId("acme-table");
        String sha = upload(pluginWithATable(id));

        ActivationPlan plan = host.plan(sha);
        assertThat(plan.activationClass()).isEqualTo(ActivationClass.BRIEF_MAINTENANCE);
        assertThat(plan.pendingChangesets()).hasSize(1);
        assertThat(plan.updateSql()).containsIgnoringCase("CREATE TABLE");

        host.activate(sha, "tester");
        runtimeIds.add(id);
        seededIds.add(id);
        awaitStatus(id, PluginInstallStatus.ACTIVE);

        assertThat(store.find(id))
                .hasValueSatisfying(e -> assertThat(e.isSchemaChanged()).isTrue());
        String schema = "plugin_" + id.replace('-', '_');
        try (Connection core =
                DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            try (Statement st = core.createStatement();
                    ResultSet rs = st.executeQuery(
                            "SELECT count(*) FROM pg_tables WHERE schemaname='" + schema + "' AND tablename='thing'")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }
    }

    @Test
    void planningAFreshInstallNeverCreatesTheSchema() throws Exception {
        String id = uniqueId("acme-planonly");
        String sha = upload(pluginWithATable(id));
        String schema = "plugin_" + id.replace('-', '_');

        ActivationPlan plan = host.plan(sha);
        assertThat(plan.activationClass()).isEqualTo(ActivationClass.BRIEF_MAINTENANCE);
        assertThat(plan.pendingChangesets()).hasSize(1);
        assertThat(plan.updateSql()).containsIgnoringCase("CREATE TABLE");

        // plan() is read-only (design.md §4: schema creation is migrate()'s job, inside the
        // advisory-locked activation sequence) — re-checking it (as a review screen polling the plan
        // would) must not have created the schema either.
        host.plan(sha);
        try (Connection core =
                DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            try (Statement st = core.createStatement();
                    ResultSet rs =
                            st.executeQuery("SELECT count(*) FROM pg_namespace WHERE nspname='" + schema + "'")) {
                rs.next();
                assertThat(rs.getInt(1)).isZero();
            }
        }

        // An admin who never activates leaves no orphaned schema behind, and a later real
        // activation still works correctly off the never-created schema.
        host.activate(sha, "tester");
        runtimeIds.add(id);
        seededIds.add(id);
        awaitStatus(id, PluginInstallStatus.ACTIVE);
        try (Connection core =
                DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            try (Statement st = core.createStatement();
                    ResultSet rs = st.executeQuery(
                            "SELECT count(*) FROM pg_tables WHERE schemaname='" + schema + "' AND tablename='thing'")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }
    }

    @Test
    void updateWithNoNewChangesetsIsInstantAndSwapsTheVersion() throws Exception {
        String id = uniqueId("acme-swap");
        String shaV1 = upload(emptyPlugin(id)
                .descriptorField("version", "1.0.0")
                .descriptorField("permissions", List.of(Map.of("action", id + ":read"))));
        host.activate(shaV1, "tester");
        runtimeIds.add(id);
        seededIds.add(id);
        awaitStatus(id, PluginInstallStatus.ACTIVE);

        String shaV2 = upload(emptyPlugin(id).descriptorField("version", "2.0.0"));
        ActivationPlan plan = host.plan(shaV2);
        assertThat(plan.fromVersion()).isEqualTo("1.0.0");
        assertThat(plan.toVersion()).isEqualTo("2.0.0");
        assertThat(plan.activationClass()).isEqualTo(ActivationClass.INSTANT);
        assertThat(plan.diff().permissionsRemoved()).containsExactly(id + ":read");

        host.activate(shaV2, "tester");
        PluginSummary active = awaitStatus(id, PluginInstallStatus.ACTIVE);
        assertThat(active.version()).isEqualTo("2.0.0");
        assertThat(active.sha256()).isEqualTo(shaV2);
        assertThat(active.previousSha256()).isEqualTo(shaV1);
    }

    @Test
    void downgradeIsRefused() throws Exception {
        String id = uniqueId("acme-downgrade");
        String shaV2 = upload(emptyPlugin(id).descriptorField("version", "2.0.0"));
        seedInstalledRow(id, shaV2);

        String shaV1 = upload(emptyPlugin(id).descriptorField("version", "1.0.0"));
        assertThatThrownBy(() -> host.plan(shaV1))
                .isInstanceOf(PluginRefusedException.class)
                .satisfies(e -> assertThat(((PluginRefusedException) e).violations())
                        .anySatisfy(v -> assertThat(v.code()).isEqualTo("downgrade-refused")));
    }

    @Test
    void vendorMismatchIsRefused() throws Exception {
        String id = uniqueId("acme-vendor");
        String shaOriginal = upload(emptyPlugin(id));
        seedInstalledRow(id, shaOriginal);

        String shaOtherVendor = upload(emptyPlugin(id)
                .descriptorField("version", "2.0.0")
                .descriptorField("vendor", Map.of("name", "Evil Corp")));
        assertThatThrownBy(() -> host.plan(shaOtherVendor))
                .isInstanceOf(PluginRefusedException.class)
                .satisfies(e -> assertThat(((PluginRefusedException) e).violations())
                        .anySatisfy(v -> assertThat(v.code()).isEqualTo("vendor-mismatch")));
    }

    @Test
    void planReportsMissingRequiresButActivateRefuses() throws Exception {
        String id = uniqueId("acme-requires");
        String sha = upload(emptyPlugin(id).descriptorField("requires", List.of("no-such-dependency")));

        ActivationPlan plan = host.plan(sha);
        assertThat(plan.missingRequires()).containsExactly("no-such-dependency");

        assertThatThrownBy(() -> host.activate(sha, "tester"))
                .isInstanceOf(PluginRefusedException.class)
                .satisfies(e -> assertThat(((PluginRefusedException) e).violations())
                        .anySatisfy(v -> assertThat(v.code()).isEqualTo("requires-missing")));
        assertThat(store.find(id)).isEmpty();
    }

    @Test
    void restartActivationClassNeedsRestartAndStartsNoRuntime() throws Exception {
        String id = uniqueId("acme-restart");
        String sha = upload(emptyPlugin(id).descriptorField("activation", "RESTART"));

        ActivationPlan plan = host.plan(sha);
        assertThat(plan.activationClass()).isEqualTo(ActivationClass.RESTART);

        host.activate(sha, "tester");
        seededIds.add(id);
        awaitStatus(id, PluginInstallStatus.NEEDS_RESTART);
        assertThat(registry.get(id)).isEmpty();
    }

    @Test
    void theNextBootStartsAPluginThatNeedsARestart() throws Exception {
        String id = uniqueId("acme-restart-boot");
        String sha = upload(emptyPlugin(id).descriptorField("activation", "RESTART"));
        host.activate(sha, "tester");
        seededIds.add(id);
        runtimeIds.add(id);
        awaitStatus(id, PluginInstallStatus.NEEDS_RESTART);

        resetBootHistory();
        host.runStartupSequence();

        assertThat(host.status(id)).get().extracting(PluginSummary::status).isEqualTo(PluginInstallStatus.ACTIVE);
        assertThat(registry.get(id)).containsInstanceOf(Active.class);
    }

    @Test
    void anUninstalledPluginsDataCannotBeTakenOverByAnotherVendor() throws Exception {
        String id = uniqueId("acme-takeover");
        String sha = upload(emptyPlugin(id));
        seedInstalledRow(id, sha);
        installs.findById(id).ifPresent(e -> {
            e.transitionTo(PluginInstallStatus.UNINSTALLED);
            installs.save(e);
        });

        String other = upload(emptyPlugin(id)
                .descriptorField("version", "2.0.0")
                .descriptorField("vendor", Map.of("name", "Evil Corp")));
        assertThatThrownBy(() -> host.plan(other))
                .isInstanceOf(PluginRefusedException.class)
                .satisfies(e -> assertThat(((PluginRefusedException) e).violations())
                        .anySatisfy(v -> assertThat(v.code()).isEqualTo("vendor-mismatch")));
    }

    @Test
    void aSecondOperationIsRefusedWhileOneIsInFlight() throws Exception {
        String id = uniqueId("acme-busy");
        String sha = upload(emptyPlugin(id));
        seedInstalledRow(id, sha);
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        Thread holder = Thread.ofVirtual()
                .start(() -> host.exclusively(() -> {
                    started.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
        try {
            started.await();
            assertThatThrownBy(() -> host.disable(id, false, "tester"))
                    .isInstanceOf(PluginRefusedException.class)
                    .satisfies(e -> assertThat(((PluginRefusedException) e).violations())
                            .anySatisfy(v -> assertThat(v.code()).isEqualTo("lifecycle-busy")));
        } finally {
            release.countDown();
            holder.join();
        }
    }

    @Test
    void connectionBudgetOver80PercentRefusesActivation() throws Exception {
        String fillerSha = store.put("filler".getBytes());
        uploadedShas.add(fillerSha);
        List<String> fillerIds = new ArrayList<>();
        int max = Integer.parseInt(
                jdbc.queryForObject("SHOW max_connections", String.class).trim());
        int needed = (int) Math.ceil((max * 0.8 - 10) / 3.0) + 1;
        try {
            for (int i = 0; i < needed; i++) {
                String fillerId = uniqueId("budget-filler-" + i);
                var entity =
                        new io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence.PluginInstallEntity(
                                fillerId, "1.0.0", "Acme", fillerSha, "seed", "{}");
                entity.transitionTo(PluginInstallStatus.ACTIVE);
                installs.save(entity);
                fillerIds.add(fillerId);
            }

            String id = uniqueId("acme-budget");
            String sha = upload(emptyPlugin(id));
            assertThatThrownBy(() -> host.activate(sha, "tester"))
                    .isInstanceOf(PluginRefusedException.class)
                    .satisfies(e -> assertThat(((PluginRefusedException) e).violations())
                            .anySatisfy(v -> assertThat(v.code()).isEqualTo("connection-budget")));
        } finally {
            fillerIds.forEach(installs::deleteById);
        }
    }

    /** Seeds a {@code plugin_install} row directly, without running an activation — for the
     * refusal tests, which only need an existing row to compare against. */
    private void seedInstalledRow(String id, String sha256) throws Exception {
        PluginDescriptor descriptor = descriptorOf(sha256);
        store.beginInstall(descriptor, sha256, json.writeValueAsString(descriptor), "seed");
        seededIds.add(id);
    }

    // ---- disable / enable / uninstall / rollback / purge (slice 2) --------------------------------

    @Test
    void disableClosesTheRuntimeAndMarksDisabled() throws Exception {
        String id = uniqueId("acme-disable");
        String sha = upload(emptyPlugin(id));
        host.activate(sha, "tester");
        runtimeIds.add(id);
        seededIds.add(id);
        awaitStatus(id, PluginInstallStatus.ACTIVE);

        host.disable(id, false, "tester");

        assertThat(host.status(id))
                .hasValueSatisfying(s -> assertThat(s.status()).isEqualTo(PluginInstallStatus.DISABLED));
        assertThat(registry.get(id)).isEmpty();
    }

    @Test
    void disableWithoutCascadeRefusesWhenAnotherActivePluginRequiresIt() throws Exception {
        String requiredId = uniqueId("acme-required");
        String requiredSha = upload(emptyPlugin(requiredId));
        host.activate(requiredSha, "tester");
        runtimeIds.add(requiredId);
        seededIds.add(requiredId);
        awaitStatus(requiredId, PluginInstallStatus.ACTIVE);

        String dependantId = uniqueId("acme-dependant");
        String dependantSha = upload(emptyPlugin(dependantId).descriptorField("requires", List.of(requiredId)));
        host.activate(dependantSha, "tester");
        runtimeIds.add(dependantId);
        seededIds.add(dependantId);
        awaitStatus(dependantId, PluginInstallStatus.ACTIVE);

        assertThat(host.dependantsOf(requiredId)).containsExactly(dependantId);
        assertThatThrownBy(() -> host.disable(requiredId, false, "tester"))
                .isInstanceOf(PluginRefusedException.class)
                .satisfies(e -> assertThat(((PluginRefusedException) e).violations())
                        .anySatisfy(v -> assertThat(v.code()).isEqualTo("requires-dependants")));

        host.disable(requiredId, true, "tester");
        assertThat(host.status(requiredId))
                .hasValueSatisfying(s -> assertThat(s.status()).isEqualTo(PluginInstallStatus.DISABLED));
        assertThat(host.status(dependantId))
                .hasValueSatisfying(s -> assertThat(s.status()).isEqualTo(PluginInstallStatus.DISABLED));
    }

    @Test
    void enableStartsTheDisabledPluginAgainInstant() throws Exception {
        String id = uniqueId("acme-enable");
        String sha = upload(emptyPlugin(id));
        host.activate(sha, "tester");
        runtimeIds.add(id);
        seededIds.add(id);
        awaitStatus(id, PluginInstallStatus.ACTIVE);
        host.disable(id, false, "tester");
        awaitStatus(id, PluginInstallStatus.DISABLED);

        ActivationPlan plan = host.enable(id, "tester");
        assertThat(plan.activationClass()).isEqualTo(ActivationClass.INSTANT);
        awaitStatus(id, PluginInstallStatus.ACTIVE);
        assertThat(registry.get(id)).containsInstanceOf(Active.class);
    }

    @Test
    void uninstallKeepsDataAndMarksUninstalled() throws Exception {
        String id = uniqueId("acme-uninstall");
        String sha = upload(pluginWithATable(id));
        host.activate(sha, "tester");
        runtimeIds.add(id);
        seededIds.add(id);
        awaitStatus(id, PluginInstallStatus.ACTIVE);

        host.uninstall(id, false, "tester");

        assertThat(host.status(id))
                .hasValueSatisfying(s -> assertThat(s.status()).isEqualTo(PluginInstallStatus.UNINSTALLED));
        assertThat(registry.get(id)).isEmpty();
        String schema = "plugin_" + id.replace('-', '_');
        assertThat(countTables(schema, "thing")).isEqualTo(1);
    }

    @Test
    void rollbackReactivatesThePreviousVersionWhenNoSchemaChanged() throws Exception {
        String id = uniqueId("acme-rollback");
        String shaV1 = upload(emptyPlugin(id).descriptorField("version", "1.0.0"));
        host.activate(shaV1, "tester");
        runtimeIds.add(id);
        seededIds.add(id);
        awaitStatus(id, PluginInstallStatus.ACTIVE);

        String shaV2 = upload(emptyPlugin(id).descriptorField("version", "2.0.0"));
        host.activate(shaV2, "tester");
        PluginSummary activeV2 = awaitStatus(id, PluginInstallStatus.ACTIVE);
        assertThat(activeV2.version()).isEqualTo("2.0.0");
        assertThat(store.find(id))
                .hasValueSatisfying(e -> assertThat(e.isSchemaChanged()).isFalse());

        ActivationPlan plan = host.rollback(id, "tester");
        assertThat(plan.activationClass()).isEqualTo(ActivationClass.INSTANT);
        assertThat(plan.toVersion()).isEqualTo("1.0.0");
        PluginSummary rolledBack = awaitStatus2(id, "1.0.0");
        assertThat(rolledBack.sha256()).isEqualTo(shaV1);
    }

    @Test
    void rollbackRefusedWhenTheCurrentVersionChangedTheSchema() throws Exception {
        String id = uniqueId("acme-rollback-schema");
        String shaV1 = upload(emptyPlugin(id).descriptorField("version", "1.0.0"));
        host.activate(shaV1, "tester");
        runtimeIds.add(id);
        seededIds.add(id);
        awaitStatus(id, PluginInstallStatus.ACTIVE);

        String shaV2 = upload(pluginWithATable(id).descriptorField("version", "2.0.0"));
        host.activate(shaV2, "tester");
        awaitStatus(id, PluginInstallStatus.ACTIVE);
        assertThat(store.find(id))
                .hasValueSatisfying(e -> assertThat(e.isSchemaChanged()).isTrue());

        assertThatThrownBy(() -> host.rollback(id, "tester"))
                .isInstanceOf(PluginRefusedException.class)
                .satisfies(e -> assertThat(((PluginRefusedException) e).violations())
                        .anySatisfy(v -> assertThat(v.code()).isEqualTo("rollback-unavailable")));
    }

    @Test
    void purgeRemovesSchemaGrantsSettingsAndTheRowAndLeavesNothingForTheSameIdLater() throws Exception {
        String id = uniqueId("acme-purge");
        String sha =
                upload(pluginWithATable(id).descriptorField("permissions", List.of(Map.of("action", id + ":admin"))));
        host.activate(sha, "tester");
        runtimeIds.add(id);
        seededIds.add(id);
        awaitStatus(id, PluginInstallStatus.ACTIVE);

        java.util.UUID roleId = java.util.UUID.randomUUID();
        jdbc.update("INSERT INTO role (id, name) VALUES (?, ?)", roleId, "purge-test-" + id);
        jdbc.update("INSERT INTO role_permission (action, role_id) VALUES (?, ?)", id + ":admin", roleId);
        jdbc.update("INSERT INTO studio_setting (key, value) VALUES (?, '\"x\"'::jsonb)", id + ".some-setting");

        PurgePlan preview = host.purgePlan(id);
        assertThat(preview.tables()).anySatisfy(t -> assertThat(t.name()).isEqualTo("thing"));
        assertThat(preview.grantsCount()).isEqualTo(1);
        assertThat(preview.settingsCount()).isEqualTo(1);

        host.uninstall(id, false, "tester");
        awaitStatus(id, PluginInstallStatus.UNINSTALLED);
        // Uninstall alone must not have purged anything yet.
        assertThat(countTables("plugin_" + id.replace('-', '_'), "thing")).isEqualTo(1);

        host.purge(id, "tester");

        assertThat(host.status(id)).isEmpty();
        assertThat(countSchemas("plugin_" + id.replace('-', '_'))).isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM role_permission WHERE action = ?", Integer.class, id + ":admin"))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM studio_setting WHERE key = ?", Integer.class, id + ".some-setting"))
                .isZero();
        assertThat(artifacts.findAllSha256()).doesNotContain(sha);
        seededIds.remove(id);
        uploadedShas.remove(sha);

        // Reusing the same id afterward must inherit nothing.
        String freshSha = upload(emptyPlugin(id));
        ActivationPlan freshPlan = host.plan(freshSha);
        assertThat(freshPlan.fromVersion()).isNull();
        host.activate(freshSha, "tester");
        runtimeIds.add(id);
        seededIds.add(id);
        PluginSummary fresh = awaitStatus(id, PluginInstallStatus.ACTIVE);
        assertThat(fresh.previousSha256()).isNull();
    }

    private int countTables(String schema, String table) throws Exception {
        try (Connection core =
                DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            try (Statement st = core.createStatement();
                    ResultSet rs = st.executeQuery("SELECT count(*) FROM pg_tables WHERE schemaname='" + schema
                            + "' AND tablename='" + table + "'")) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private int countSchemas(String schema) throws Exception {
        try (Connection core =
                DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            try (Statement st = core.createStatement();
                    ResultSet rs = st.executeQuery(
                            "SELECT count(*) FROM information_schema.schemata WHERE schema_name='" + schema + "'")) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** Like {@link #awaitStatus(String, PluginInstallStatus)}, but for rollback, which stays ACTIVE
     * throughout — waits for the version to change instead. */
    private PluginSummary awaitStatus2(String id, String wantVersion) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(deadline)) {
            var summary = host.status(id);
            if (summary.isPresent()
                    && summary.get().status() == PluginInstallStatus.ACTIVE
                    && wantVersion.equals(summary.get().version())) {
                return summary.get();
            }
            if (summary.isPresent() && summary.get().status() == PluginInstallStatus.FAILED) {
                throw new AssertionError("Plugin '" + id + "' failed while waiting for version " + wantVersion + ": "
                        + summary.get().failure());
            }
            Thread.sleep(50);
        }
        throw new AssertionError(
                "Plugin '" + id + "' never reached version " + wantVersion + "; last status: " + host.status(id));
    }

    // ---- boot / shutdown / safe mode (task 6.8's SmartLifecycle half) ---------------------------

    /** A clean slate for {@code studio_boot} so a test's own boot history is the only history
     * {@link PluginHost#runStartupSequence()} sees — a stray row from another test would otherwise
     * make the crash-loop decision non-deterministic. */
    private void resetBootHistory() {
        jdbc.update("DELETE FROM studio_boot");
    }

    @Test
    void staleActivatingRowIsFailedAtBootBecauseNothingHoldsItsAdvisoryLock() throws Exception {
        String id = uniqueId("acme-stale");
        String sha = store.put(("not-a-real-jar-" + id).getBytes());
        uploadedShas.add(sha);
        installs.save(new PluginInstallEntity(id, "1.0.0", "Acme", sha, "tester", descriptorJson(id)));
        seededIds.add(id);
        assertThat(host.status(id)).get().extracting(PluginSummary::status).isEqualTo(PluginInstallStatus.ACTIVATING);

        resetBootHistory();
        host.runStartupSequence();

        assertThat(host.status(id)).get().satisfies(s -> {
            assertThat(s.status()).isEqualTo(PluginInstallStatus.FAILED);
            assertThat(s.failure()).contains("Studio stopped while activating");
        });
    }

    @Test
    void crashLoopAfterThreeUncleanBootsTripsSafeModeAndStartsNoPlugin() throws Exception {
        String id = uniqueId("acme-crashloop");
        String sha = store.put(("not-a-real-jar-" + id).getBytes());
        uploadedShas.add(sha);
        PluginInstallEntity entity = new PluginInstallEntity(id, "1.0.0", "Acme", sha, "tester", descriptorJson(id));
        entity.transitionTo(PluginInstallStatus.ACTIVE);
        installs.save(entity);
        seededIds.add(id);

        resetBootHistory();
        for (int i = 0; i < 3; i++) {
            jdbc.update("INSERT INTO studio_boot (started_at) VALUES (now() - interval '1 minute')");
        }

        host.runStartupSequence();
        try {
            assertThat(host.safeMode()).isTrue();
            assertThat(host.safeModeReason()).get().asString().contains("uncleanly");
            // Safe mode starts no plugin: the seeded ACTIVE row is untouched by boot (still
            // unregistered in the runtime registry — nothing ever built a runtime for it).
            assertThat(registry.get(id)).isEmpty();
        } finally {
            // Leave the singleton host back in its normal, non-safe-mode state for every later test.
            // The seeded row's sha isn't a real jar, so a plain reboot would fail it (harmlessly);
            // remove it first so the reset boot only clears safe mode, nothing else.
            installs.deleteById(id);
            seededIds.remove(id);
            resetBootHistory();
            host.runStartupSequence();
            assertThat(host.safeMode()).isFalse();
        }
    }

    @Test
    void stopClosesRunningPluginsAndRecordsACleanStopAndSurvivesStartAfterwards() throws Exception {
        String id = uniqueId("acme-stopstart");
        String sha = upload(emptyPlugin(id));
        host.activate(sha, "tester");
        runtimeIds.add(id);
        seededIds.add(id);
        awaitStatus(id, PluginInstallStatus.ACTIVE);
        assertThat(registry.get(id)).isPresent();

        resetBootHistory();
        host.runStartupSequence();
        assertThat(host.isRunning()).isTrue();

        host.stop();
        try {
            assertThat(host.isRunning()).isFalse();
            assertThat(registry.get(id)).isEmpty();
            Long stoppedCount =
                    jdbc.queryForObject("SELECT count(*) FROM studio_boot WHERE stopped_at IS NOT NULL", Long.class);
            assertThat(stoppedCount).isEqualTo(1L);
        } finally {
            host.start();
            assertThat(host.isRunning()).isTrue();
            runtimeIds.remove(id);
        }
    }

    private String descriptorJson(String id) {
        return json.writeValueAsString(
                io.github.sudoitir.artemisstudio.kernel.plugin.support.PluginJarBuilder.defaultDescriptor(id));
    }
}
