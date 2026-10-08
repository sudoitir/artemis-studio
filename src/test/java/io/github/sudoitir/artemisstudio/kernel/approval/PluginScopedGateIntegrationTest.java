package io.github.sudoitir.artemisstudio.kernel.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.jayway.jsonpath.JsonPath;
import io.github.sudoitir.artemisstudio.kernel.gate.GatedOperationRegistry;
import io.github.sudoitir.artemisstudio.kernel.gate.HeldState;
import io.github.sudoitir.artemisstudio.kernel.gate.Operation;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.host.PluginHost;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence.PluginArtifactRepository;
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
 * A plugin that is the approval provider and also gates its own operation through the {@code OperationGate} the host
 * puts in its context: a request to its endpoint is held, a second person approves it and its replay runs once.
 * Another plugin's gate refuses it, because a plugin may gate only its own types.
 */
class PluginScopedGateIntegrationTest extends GatedAccessTestBase {

    @LocalServerPort
    int port;

    @Autowired
    PluginHost host;

    @Autowired
    PluginRuntimeRegistry registry;

    @Autowired
    PluginArtifactRepository artifacts;

    @Autowired
    GateEngine engine;

    @Autowired
    GatedOperationRegistry operations;

    private final String pluginId = "own-gate-" + Long.toString(System.nanoTime(), 36);
    private String sha;

    @AfterEach
    void removeThePlugin() {
        registry.get(pluginId).ifPresent(slot -> {
            if (slot instanceof PluginRuntimeRegistry.Active active) {
                active.runtime().close();
            }
        });
        registry.remove(pluginId);
        installs.findById(pluginId).ifPresent(installs::delete);
        if (sha != null) {
            jdbc.update("DELETE FROM plugin_upload WHERE sha256 = ?", sha);
            artifacts.deleteById(sha);
        }
        System.clearProperty(runs());
    }

    private String runs() {
        return "plugin-scoped-gate.runs." + pluginId;
    }

    /** A provider that holds everything, and a {@code <id>:thing} operation behind {@code POST /thing}. */
    private PluginJarBuilder gatingPlugin() {
        PluginJarBuilder builder = new PluginJarBuilder(pluginId)
                .descriptorField(
                        "permissions",
                        List.of(Map.of("action", pluginId + ":approve", "description", "Approve", "scope", "global")))
                .descriptorField("approvalProvider", Map.of("approverPermission", pluginId + ":approve"));
        String pkg = builder.basePackage();
        return builder.source(pkg + ".HoldEverything", """
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
                        """.formatted(pkg))
                .source(pkg + ".ThingParams", """
                        package %s;
                        public record ThingParams(String name) {}
                        """.formatted(pkg))
                .source(pkg + ".ThingService", """
                        package %s;
                        import io.github.sudoitir.artemisstudio.kernel.gate.*;
                        import org.springframework.stereotype.Component;
                        @Component
                        public class ThingService {
                            private final OperationGate gate;
                            public ThingService(OperationGate gate) { this.gate = gate; }
                            public String run(String name) {
                                return gate.run(Operation.of(new ThingParams(name)), () -> {
                                    String key = "%s";
                                    System.setProperty(key, String.valueOf(Integer.getInteger(key, 0) + 1));
                                    return "done";
                                });
                            }
                        }
                        """.formatted(pkg, runs()))
                .source(pkg + ".ThingOperation", """
                        package %s;
                        import io.github.sudoitir.artemisstudio.kernel.gate.*;
                        import java.util.List;
                        import java.util.Set;
                        import org.springframework.stereotype.Component;
                        @Component
                        public class ThingOperation implements GatedOperation<ThingParams> {
                            private final org.springframework.beans.factory.ObjectProvider<ThingService> service;
                            public ThingOperation(org.springframework.beans.factory.ObjectProvider<ThingService> service) {
                                this.service = service;
                            }
                            public String type() { return "%s:thing"; }
                            public int version() { return 1; }
                            public Class<ThingParams> paramsType() { return ThingParams.class; }
                            public Set<Trait> traits(ThingParams p) { return Set.of(); }
                            public ExecutionMode mode() { return ExecutionMode.ON_APPROVAL; }
                            public OperationScope scope(ThingParams p) { return OperationScope.GLOBAL; }
                            public String summary(ThingParams p) { return "Thing " + p.name(); }
                            public List<DisplayRow> display(ThingParams p) { return List.of(); }
                            public Set<String> redactedPaths() { return Set.of(); }
                            public Effect estimate(ThingParams p) { return new Effect(1, "thing", "k", null); }
                            public void replay(ThingParams p) { service.getObject().run(p.name()); }
                        }
                        """.formatted(pkg, pluginId))
                .source(pkg + ".ThingController", """
                        package %s;
                        import org.springframework.web.bind.annotation.*;
                        @RestController
                        @RequestMapping("/api/v1/p/%s")
                        public class ThingController {
                            private final ThingService service;
                            public ThingController(ThingService service) { this.service = service; }
                            @PostMapping("/thing")
                            public String thing(@RequestParam String name) { return service.run(name); }
                        }
                        """.formatted(pkg, pluginId));
    }

    @Test
    void aPluginGatesItsOwnOperationAndApprovalReplaysItOnce() throws Exception {
        TrustedTestKey.trust(jdbc);
        sha = host.inspect(gatingPlugin().build(), "tester").sha256();
        host.activate(sha, "tester", true);
        await("the plugin becomes active and arms the gate")
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(50))
                .until(() ->
                        providers.armedProviderId().filter(pluginId::equals).isPresent()
                                && providers.attached(pluginId).isPresent());
        Person alice = requester();
        Person bob = newUser(pluginId + ":approve");
        when(holders.holders(any(), eq(pluginId + ":approve"), anyInt())).thenReturn(List.of(bob.id()));
        Browser requesterBrowser = new Browser(port, "test", "198.51.100.31");
        Browser approverBrowser = new Browser(port, "test", "198.51.100.32");
        assertThat(requesterBrowser.post("/api/v1/auth/login", login(alice)).statusCode())
                .isEqualTo(200);
        assertThat(approverBrowser.post("/api/v1/auth/login", login(bob)).statusCode())
                .isEqualTo(200);

        HttpResponse<String> held = requesterBrowser.post("/api/v1/p/" + pluginId + "/thing?name=a", null);

        assertThat(held.statusCode()).as(held.body()).isEqualTo(202);
        UUID id = UUID.fromString(JsonPath.read(held.body(), "$.heldOperation.id"));
        assertThat(System.getProperty(runs())).isNull();
        String detail = approverBrowser
                .send("GET", "/api/v1/held-operations/" + id, null)
                .body();
        HttpResponse<String> decided = approverBrowser.post(
                "/api/v1/held-operations/" + id + "/decision",
                "{\"vote\":\"APPROVE\",\"paramsHash\":\"%s\",\"version\":%d}"
                        .formatted(
                                JsonPath.<String>read(detail, "$.paramsHash"),
                                JsonPath.<Integer>read(detail, "$.version")));
        assertThat(decided.statusCode()).as(decided.body()).isEqualTo(200);

        awaitState(id, HeldState.SUCCEEDED);
        assertThat(System.getProperty(runs())).isEqualTo("1");
    }

    @Test
    void anotherPluginsGateRefusesTheOperation() throws Exception {
        TrustedTestKey.trust(jdbc);
        sha = host.inspect(gatingPlugin().build(), "tester").sha256();
        host.activate(sha, "tester", true);
        await("the plugin's operation is registered")
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> operations.forType(pluginId + ":thing").isPresent());
        Class<? extends Record> params =
                operations.forType(pluginId + ":thing").orElseThrow().paramsType();
        Record thing = (Record) params.getDeclaredConstructors()[0].newInstance("a");
        Object other = engine.beansFor("someone-else").get(GateEngine.PLUGIN_BEAN_NAME);

        assertThatThrownBy(() -> ((io.github.sudoitir.artemisstudio.kernel.gate.OperationGate) other)
                        .run(Operation.of(thing), () -> "ran"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(pluginId + ":thing");
    }

    private static String login(Person person) {
        return "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(person.username(), PASSWORD);
    }
}
