package io.github.sudoitir.artemisstudio.kernel.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginInstallStatus;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginStatusChanged;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.host.PluginHost;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.host.PluginRefusedException;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence.PluginArtifactRepository;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence.PluginInstallEntity;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence.PluginInstallRepository;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.runtime.PluginRuntimeRegistry;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.store.PluginStore;
import io.github.sudoitir.artemisstudio.kernel.plugin.support.ApprovalProviderPlugin;
import io.github.sudoitir.artemisstudio.kernel.plugin.support.PluginJarBuilder;
import io.github.sudoitir.artemisstudio.kernel.plugin.support.TrustedTestKey;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The approval provider's place in the plugin lifecycle (ADR-0179): the descriptor block arms the
 * gate through {@code plugin_install}, a second provider is refused, the provider and the
 * operations a plugin contributes are found while it is attached, and removing it is announced
 * inside the transaction that does it.
 */
class ApprovalProviderIntegrationTest extends PostgresIntegrationTest {

    private record Seen(String pluginId, PluginInstallStatus from, PluginInstallStatus to, boolean inTransaction) {}

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
    ApprovalProviderRegistry providers;

    @Autowired
    GatedOperationRegistry operations;

    @Autowired
    ApplicationEventMulticaster events;

    @Autowired
    JdbcTemplate jdbc;

    private final List<String> pluginIds = new ArrayList<>();
    private final List<String> shas = new ArrayList<>();
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private final ApplicationListener<ApplicationEvent> listener = event -> {
        if (event instanceof PayloadApplicationEvent<?> payload
                && payload.getPayload() instanceof PluginStatusChanged changed) {
            seen.add(new Seen(
                    changed.pluginId(),
                    changed.from(),
                    changed.to(),
                    TransactionSynchronizationManager.isActualTransactionActive()));
        }
    };

    @BeforeEach
    void listenAndTrust() {
        TrustedTestKey.trust(jdbc);
        // Another test class may have left an approval provider behind.
        jdbc.update("UPDATE plugin_install SET approval_provider = false");
        events.addApplicationListener(listener);
    }

    @AfterEach
    void cleanUp() {
        events.removeApplicationListener(listener);
        for (String id : pluginIds) {
            registry.get(id).ifPresent(slot -> {
                if (slot instanceof PluginRuntimeRegistry.Active active) {
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
    }

    private static String newId() {
        return "gate-" + Long.toString(System.nanoTime(), 36);
    }

    private String upload(PluginJarBuilder builder) throws Exception {
        String sha = store.put(Files.readAllBytes(builder.build()));
        shas.add(sha);
        return sha;
    }

    private void activate(String id, PluginJarBuilder builder) throws Exception {
        if (!pluginIds.contains(id)) {
            pluginIds.add(id);
        }
        String sha = upload(builder);
        host.activate(sha, "tester", true);
        awaitActive(id, sha);
    }

    private void awaitActive(String id, String sha) {
        await("plugin '" + id + "' becomes active")
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> {
                    var entity = installs.findById(id);
                    if (entity.isPresent() && entity.get().status() == PluginInstallStatus.FAILED) {
                        throw new AssertionError(entity.get().getFailure());
                    }
                    return entity.isPresent()
                            && entity.get().status() == PluginInstallStatus.ACTIVE
                            && entity.get().getSha256().equals(sha);
                });
    }

    @Test
    void theDescriptorBlockArmsTheGateAndTheProviderAndItsOperationsAreFoundWhileAttached() throws Exception {
        String id = newId();
        assertThat(providers.armedProviderId()).isEmpty();

        activate(id, ApprovalProviderPlugin.jar(id, "1.0.0"));

        assertThat(providers.armedProviderId()).contains(id);
        assertThat(installs.findById(id))
                .get()
                .satisfies(e -> assertThat(e.isApprovalProvider()).isTrue());
        ApprovalProvider provider = providers.attached().orElseThrow();
        assertThat(provider.decide(null)).isInstanceOf(GateDecision.Allow.class);
        assertThat(operations.forType(id + ":thing")).isPresent();

        host.disable(id, false, "tester");

        assertThat(providers.armedProviderId()).isEmpty();
        assertThat(providers.attached()).isEmpty();
        assertThat(operations.forType(id + ":thing")).isEmpty();
    }

    @Test
    void everyStatusThatMeansToBeActiveKeepsTheGateArmed() {
        String id = newId();
        String sha = store.put(("not-a-real-jar-" + id).getBytes());
        shas.add(sha);
        PluginInstallEntity entity = new PluginInstallEntity(id, "1.0.0", "Acme", sha, "tester", "{}");
        entity.approvalProvider(true);
        installs.save(entity);
        pluginIds.add(id);

        for (PluginInstallStatus status : PluginInstallStatus.values()) {
            jdbc.update("UPDATE plugin_install SET status = ? WHERE id = ?", status.dbValue(), id);
            assertThat(providers.armedProviderId())
                    .as(status.name())
                    .isEqualTo(status.desiredActive() ? java.util.Optional.of(id) : java.util.Optional.empty());
        }
        assertThat(PluginInstallStatus.DISABLED.desiredActive()).isFalse();
        assertThat(PluginInstallStatus.UNINSTALLED.desiredActive()).isFalse();
        assertThat(PluginInstallStatus.FAILED.desiredActive()).isTrue();
    }

    @Test
    void aSecondApprovalProviderIsRefusedUntilTheFirstIsDisabled() throws Exception {
        String first = newId();
        String second = newId();
        activate(first, ApprovalProviderPlugin.jar(first, "1.0.0"));
        String sha = upload(ApprovalProviderPlugin.jar(second, "1.0.0"));
        pluginIds.add(second);

        assertThatThrownBy(() -> host.activate(sha, "tester", true))
                .isInstanceOf(PluginRefusedException.class)
                .satisfies(e -> assertThat(((PluginRefusedException) e).violations())
                        .anySatisfy(v -> {
                            assertThat(v.code()).isEqualTo("approval-provider-exists");
                            assertThat(v.message()).contains(first);
                        }));
        assertThat(installs.findById(second)).isEmpty();

        host.disable(first, false, "tester");
        host.activate(sha, "tester", true);
        awaitActive(second, sha);
        assertThat(providers.armedProviderId()).contains(second);
    }

    @Test
    void aFailedProviderStillCountsAsTheOneProvider() throws Exception {
        String first = newId();
        activate(first, ApprovalProviderPlugin.jar(first, "1.0.0"));
        jdbc.update("UPDATE plugin_install SET status = 'failed' WHERE id = ?", first);
        String second = newId();
        String sha = upload(ApprovalProviderPlugin.jar(second, "1.0.0"));
        pluginIds.add(second);

        assertThatThrownBy(() -> host.activate(sha, "tester", true)).isInstanceOf(PluginRefusedException.class);
    }

    @Test
    void disablingAndUninstallingAnnounceTheChangeInsideTheTransaction() throws Exception {
        String id = newId();
        activate(id, ApprovalProviderPlugin.jar(id, "1.0.0"));

        host.disable(id, false, "tester");
        host.uninstall(id, false, "tester");

        assertThat(seen).containsExactly(new Seen(id, PluginInstallStatus.ACTIVE, PluginInstallStatus.DISABLED, true));
    }

    @Test
    void uninstallingAnActivePluginAnnouncesIt() throws Exception {
        String id = newId();
        activate(id, ApprovalProviderPlugin.jar(id, "1.0.0"));

        host.uninstall(id, false, "tester");

        assertThat(seen)
                .containsExactly(new Seen(id, PluginInstallStatus.ACTIVE, PluginInstallStatus.UNINSTALLED, true));
    }

    @Test
    void anUpgradeAndARollbackAnnounceNothingAndRollbackRestoresTheFlag() throws Exception {
        String id = newId();
        activate(id, ApprovalProviderPlugin.jar(id, "1.0.0"));
        // 1.1.0 stops being a provider, as a plugin that never declared the block.
        activate(id, new PluginJarBuilder(id).descriptorField("version", "1.1.0"));

        assertThat(installs.findById(id))
                .get()
                .satisfies(e -> assertThat(e.isApprovalProvider()).isFalse());
        assertThat(providers.armedProviderId()).isEmpty();

        host.rollback(id, "tester", true);
        await().atMost(Duration.ofSeconds(30))
                .until(() -> installs.findById(id)
                        .filter(e -> e.getVersion().equals("1.0.0") && e.status() == PluginInstallStatus.ACTIVE)
                        .isPresent());

        assertThat(installs.findById(id))
                .get()
                .satisfies(e -> assertThat(e.isApprovalProvider()).isTrue());
        assertThat(providers.armedProviderId()).contains(id);
        assertThat(seen).isEmpty();
    }

    @Test
    void aProviderWithoutItsBeanFailsTheActivationAndStaysArmed() throws Exception {
        String id = newId();
        pluginIds.add(id);
        String sha = upload(ApprovalProviderPlugin.withProviderBlock(new PluginJarBuilder(id), id));

        host.activate(sha, "tester", true);

        await().atMost(Duration.ofSeconds(30))
                .until(() -> installs.findById(id)
                        .filter(e -> e.status() == PluginInstallStatus.FAILED)
                        .isPresent());
        assertThat(installs.findById(id))
                .get()
                .satisfies(e -> assertThat(e.getFailure()).contains("ApprovalProvider"));
        assertThat(providers.armedProviderId()).contains(id);
        assertThat(providers.attached()).isEmpty();
    }
}
