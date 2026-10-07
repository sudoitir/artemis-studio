package io.github.sudoitir.artemisstudio.kernel.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.jayway.jsonpath.JsonPath;
import io.github.sudoitir.artemisstudio.kernel.gate.HeldState;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginInstallStatus;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.host.PluginHost;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence.PluginArtifactRepository;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence.PluginInstallEntity;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.runtime.PluginRuntimeRegistry;
import io.github.sudoitir.artemisstudio.kernel.plugin.support.PluginJarBuilder;
import io.github.sudoitir.artemisstudio.kernel.plugin.support.TrustedTestKey;
import io.github.sudoitir.artemisstudio.support.Browser;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * An approval provider from a plugin jar alone (ADR-0179): a third party declares {@code approvalProvider} and
 * implements {@code ApprovalProvider}, and Studio holds, lets a second person approve and replays an operation once,
 * with nothing of Studio's changed for it.
 */
class ThirdPartyApprovalProviderIntegrationTest extends GatedAccessTestBase {

    @LocalServerPort
    int port;

    @Autowired
    PluginHost host;

    @Autowired
    PluginRuntimeRegistry registry;

    @Autowired
    PluginArtifactRepository artifacts;

    private final String providerId = "hold-all-" + Long.toString(System.nanoTime(), 36);
    private final String targetId = "gate-purged-" + Long.toString(System.nanoTime(), 36);
    private String providerSha;

    @AfterEach
    void removeThePlugins() {
        registry.get(providerId).ifPresent(slot -> {
            if (slot instanceof PluginRuntimeRegistry.Active active) {
                active.runtime().close();
            }
        });
        registry.remove(providerId);
        installs.findById(providerId).ifPresent(installs::delete);
        installs.findById(targetId).ifPresent(installs::delete);
        if (providerSha != null) {
            jdbc.update("DELETE FROM plugin_upload WHERE sha256 = ?", providerSha);
            artifacts.deleteById(providerSha);
        }
    }

    /** A plugin that holds every gated operation for anyone with {@code <id>:approve}. */
    private PluginJarBuilder holdEverything() {
        PluginJarBuilder builder = new PluginJarBuilder(providerId)
                .descriptorField(
                        "permissions",
                        List.of(Map.of("action", providerId + ":approve", "description", "Approve", "scope", "global")))
                .descriptorField("approvalProvider", Map.of("approverPermission", providerId + ":approve"));
        return builder.source(builder.basePackage() + ".HoldEverything", """
                package %s;
                import io.github.sudoitir.artemisstudio.kernel.gate.*;
                import java.time.Duration;
                import org.springframework.stereotype.Component;
                @Component
                public class HoldEverything implements ApprovalProvider {
                    public GateDecision decide(GateRequest request) {
                        return new GateDecision.Hold(
                                new PolicyRef("everything", "1", "Hold everything"), Duration.ofHours(1), false,
                                "Anyone who may approve");
                    }
                    public VoteCheck checkVote(HeldOperationView held, Approver approver, Vote vote) {
                        return VoteCheck.allow();
                    }
                    public RunCheck checkRun(HeldOperationView held, Effect now) {
                        return RunCheck.allow();
                    }
                }
                """.formatted(builder.basePackage()));
    }

    @Test
    void aProviderFromAJarHoldsAPurgeThatASecondPersonApprovesAndItRunsOnce() throws Exception {
        TrustedTestKey.trust(jdbc);
        providerSha = host.inspect(holdEverything().build(), "tester").sha256();
        host.activate(providerSha, "tester", true);
        await("the provider plugin becomes active and arms the gate")
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(50))
                .until(() ->
                        providers.armedProviderId().filter(providerId::equals).isPresent()
                                && providers.attached(providerId).isPresent());
        String sha = pluginStore.put(("gate-purged-" + UUID.randomUUID()).getBytes());
        installs.save(new PluginInstallEntity(
                targetId, "1.0.0", "Acme", sha, "tester", storedDescriptor(targetId, Map.of())));
        jdbc.update("UPDATE plugin_install SET status = 'uninstalled' WHERE id = ?", targetId);
        Person alice = requester();
        Person bob = newUser(providerId + ":approve");
        when(holders.holders(any(), eq(providerId + ":approve"), anyInt())).thenReturn(List.of(bob.id()));
        Browser requester = new Browser(port, "test", "198.51.100.21");
        Browser approver = new Browser(port, "test", "198.51.100.22");
        assertThat(requester.post("/api/v1/auth/login", login(alice)).statusCode())
                .isEqualTo(200);
        assertThat(approver.post("/api/v1/auth/login", login(bob)).statusCode()).isEqualTo(200);

        HttpResponse<String> held = requester.post("/api/v1/admin/plugins/" + targetId + "/purge", null);

        assertThat(held.statusCode()).as(held.body()).isEqualTo(202);
        UUID id = UUID.fromString(JsonPath.read(held.body(), "$.heldOperation.id"));
        assertThat(installs.findById(targetId)).isPresent();
        String detail =
                approver.send("GET", "/api/v1/held-operations/" + id, null).body();
        HttpResponse<String> decided = approver.post(
                "/api/v1/held-operations/" + id + "/decision",
                "{\"vote\":\"APPROVE\",\"paramsHash\":\"%s\",\"version\":%d}"
                        .formatted(
                                JsonPath.<String>read(detail, "$.paramsHash"),
                                JsonPath.<Integer>read(detail, "$.version")));
        assertThat(decided.statusCode()).as(decided.body()).isEqualTo(200);

        awaitState(id, HeldState.SUCCEEDED);
        assertThat(installs.findById(targetId)).isEmpty();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM audit_event WHERE action = 'PLUGIN_PURGE' AND target_name = ?",
                        Long.class,
                        targetId))
                .isOne();
        assertThat(installs.findById(providerId).orElseThrow().status()).isEqualTo(PluginInstallStatus.ACTIVE);
    }

    private static String login(Person person) {
        return "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(person.username(), PASSWORD);
    }
}
