package io.github.sudoitir.artemisstudio.kernel.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jayway.jsonpath.JsonPath;
import io.github.sudoitir.artemisstudio.kernel.core.StudioHealth;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.descriptor.PluginDescriptorParser;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.runtime.PluginRuntimeFactory;
import io.github.sudoitir.artemisstudio.kernel.plugin.support.PluginJarBuilder;
import io.github.sudoitir.artemisstudio.kernel.plugin.support.SignInPlugin;
import io.github.sudoitir.artemisstudio.kernel.plugin.support.SignInProbe;
import io.github.sudoitir.artemisstudio.kernel.security.internal.PluginSignInHealthIndicator;
import io.github.sudoitir.artemisstudio.support.Browser;
import io.github.sudoitir.artemisstudio.support.SignInPluginIntegrationTest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.context.WebApplicationContext;

/**
 * A plugin's sign-in provider on the one login path (ADR-0153), over real HTTP: it signs users in
 * and maps their groups, and everything Studio owns on that path, from the answer to a wrong
 * password to the lockout, is what it is for a local account. A plugin that fails, hangs or loses
 * its signer stops signing anyone in, and nothing else notices.
 */
class PluginSignInIntegrationTest extends SignInPluginIntegrationTest {

    @Autowired
    PluginSignInHealthIndicator health;

    @Autowired
    PluginRuntimeFactory runtimeFactory;

    @Autowired
    PluginDescriptorParser descriptorParser;

    @Autowired
    WebApplicationContext webContext;

    @Test
    void aUserSignsInThroughTheProviderAndItsGroupsAreMapped() throws Exception {
        String id = installSignInPlugin(false);
        String provider = provider(id);
        SignInProbe.of(provider).add("uid=alice", "alice", DIRECTORY_PASSWORD, "eng");
        mapGroup(provider, "eng", Permissions.CLUSTER_READ);

        Browser alice = signedInVia(provider, "alice", DIRECTORY_PASSWORD);

        assertThat(alice.status("GET", CONSOLE)).isEqualTo(200);
        var me = alice.send("GET", ME, null);
        assertThat((String) JsonPath.read(me.body(), "$.username")).isEqualTo("alice");
        assertThat((List<String>) JsonPath.read(me.body(), "$.grants[*].permissions[*]"))
                .containsExactly(Permissions.CLUSTER_READ);
        assertThat(account(provider, "uid=alice").getProviderId()).isEqualTo(provider);
        assertThat(providerIds()).contains(provider);
        var listed = browser().send("GET", "/api/v1/auth/providers", null);
        assertThat((List<String>) JsonPath.read(listed.body(), "$.data[?(@.id=='" + provider + "')].label"))
                .containsExactly("Corporate directory");
    }

    @Test
    void aWrongPasswordLooksLikeALocalOneAndCountsTowardTheLockout() throws Exception {
        String id = installSignInPlugin(false);
        String provider = provider(id);
        SignInProbe.of(provider).add("uid=carol", "carol", DIRECTORY_PASSWORD, "eng");
        mapGroup(provider, "eng", Permissions.CLUSTER_READ);
        signedInVia(provider, "carol", DIRECTORY_PASSWORD);
        newUser("local-wrong");

        HttpResponse<String> viaPlugin = loginVia(browser(), provider, "carol", "wrong");
        HttpResponse<String> local = loginVia(browser(), "local", "local-wrong", "wrong");

        assertThat(viaPlugin.statusCode()).isEqualTo(401).isEqualTo(local.statusCode());
        assertThat((String) JsonPath.read(viaPlugin.body(), "$.type")).isEqualTo(JsonPath.read(local.body(), "$.type"));
        assertThat((String) JsonPath.read(viaPlugin.body(), "$.detail"))
                .isEqualTo(JsonPath.read(local.body(), "$.detail"));
        for (int i = 0; i < 9; i++) {
            assertThat(loginVia(browser(), provider, "carol", "wrong").statusCode())
                    .isEqualTo(401);
        }
        // Ten wrong passwords lock the account: the right one is now refused like a wrong one.
        assertThat(loginVia(browser(), provider, "carol", DIRECTORY_PASSWORD).statusCode())
                .isEqualTo(401);
        assertThat(account(provider, "uid=carol").getLockedUntil()).isNotNull();
    }

    @Test
    void aUsernameEqualToALocalAdministratorsIsASeparateAccountWithOnlyMappedGrants() throws Exception {
        UUID localAdmin = newAdministrator("dave");
        String id = installSignInPlugin(false);
        String provider = provider(id);
        SignInProbe.of(provider).add("uid=dave", "dave", DIRECTORY_PASSWORD, "eng");
        mapGroup(provider, "eng", Permissions.CLUSTER_READ);

        Browser dave = signedInVia(provider, "dave", DIRECTORY_PASSWORD);

        var account = account(provider, "uid=dave");
        assertThat(account.getUsername()).isEqualTo("dave@" + provider);
        assertThat(account.getId()).isNotEqualTo(localAdmin);
        var me = dave.send("GET", ME, null);
        assertThat((List<String>) JsonPath.read(me.body(), "$.grants[*].permissions[*]"))
                .containsExactly(Permissions.CLUSTER_READ);
        // Typing the plain name at the provider is the directory's user, and the local one is untouched.
        assertThat(login(browser(), "dave").statusCode()).isEqualTo(200);
    }

    @Test
    void aThrowingProviderFailsLikeAWrongPasswordAndHealthRecovers() throws Exception {
        String id = installSignInPlugin(false);
        String provider = provider(id);
        var directory = SignInProbe.of(provider).add("uid=erin", "erin", DIRECTORY_PASSWORD, "eng");
        mapGroup(provider, "eng", Permissions.CLUSTER_READ);
        newUser("local-while-down");
        assertThat(health.health().getStatus().getCode()).isEqualTo("UP");

        directory.throwing = true;
        HttpResponse<String> response = loginVia(browser(), provider, "erin", DIRECTORY_PASSWORD);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(health.health().getStatus()).isEqualTo(StudioHealth.DEGRADED);
        assertThat(health.health().getDetails().get("failing").toString())
                .contains(provider)
                .contains("the directory is down");
        assertThat(signedIn("local-while-down").status("GET", CONSOLE)).isEqualTo(200);

        directory.throwing = false;
        signedInVia(provider, "erin", DIRECTORY_PASSWORD);
        assertThat(health.health().getStatus().getCode()).isEqualTo("UP");
    }

    @Test
    void aProviderThatDoesNotAnswerInTimeFailsAndHealthRecovers() throws Exception {
        String id = installSignInPlugin(false);
        String provider = provider(id);
        var directory = SignInProbe.of(provider).add("uid=frank", "frank", DIRECTORY_PASSWORD, "eng");
        mapGroup(provider, "eng", Permissions.CLUSTER_READ);
        directory.delayMillis = 8_000;

        long started = System.nanoTime();
        HttpResponse<String> response = loginVia(browser(), provider, "frank", DIRECTORY_PASSWORD);
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(took).isBetween(Duration.ofSeconds(4), Duration.ofSeconds(7));
        assertThat(health.health().getStatus()).isEqualTo(StudioHealth.DEGRADED);
        assertThat(health.health().getDetails().get("failing").toString()).contains("did not answer within 5 s");
        newUser("local-while-slow");
        assertThat(signedIn("local-while-slow").status("GET", CONSOLE)).isEqualTo(200);

        directory.delayMillis = 0;
        signedInVia(provider, "frank", DIRECTORY_PASSWORD);
        assertThat(health.health().getStatus().getCode()).isEqualTo("UP");
    }

    @Test
    void anInvalidAnswerFailsTheSignIn() throws Exception {
        String id = installSignInPlugin(false);
        String provider = provider(id);
        // A subject longer than 255 characters is refused, whatever the password.
        SignInProbe.of(provider).add("s".repeat(256), "grace", DIRECTORY_PASSWORD, "eng");
        mapGroup(provider, "eng", Permissions.CLUSTER_READ);

        assertThat(loginVia(browser(), provider, "grace", DIRECTORY_PASSWORD).statusCode())
                .isEqualTo(401);
        assertThat(health.health().getStatus()).isEqualTo(StudioHealth.DEGRADED);
    }

    @Test
    void stoppingThePluginWithdrawsItsProvider() throws Exception {
        String id = installSignInPlugin(false);
        String provider = provider(id);
        SignInProbe.of(provider).add("uid=heidi", "heidi", DIRECTORY_PASSWORD, "eng");
        mapGroup(provider, "eng", Permissions.CLUSTER_READ);
        Browser heidi = signedInVia(provider, "heidi", DIRECTORY_PASSWORD);
        assertThat(providerIds()).contains(provider);

        host.disable(id, false, "tester");

        assertThat(providerIds()).doesNotContain(provider);
        assertThat(loginVia(browser(), provider, "heidi", DIRECTORY_PASSWORD).statusCode())
                .isEqualTo(401);
        // Stopping the plugin does not sign anyone out: an update stops it too.
        assertThat(heidi.status("GET", CONSOLE)).isEqualTo(200);
    }

    @Test
    void removingTheSignersKeyWithdrawsTheProviderWhileThePluginKeepsRunning() throws Exception {
        String id = installSignInPlugin(false);
        String provider = provider(id);
        SignInProbe.of(provider).add("uid=ivan", "ivan", DIRECTORY_PASSWORD, "eng");
        mapGroup(provider, "eng", Permissions.CLUSTER_READ);
        signedInVia(provider, "ivan", DIRECTORY_PASSWORD);

        trust.remove(PUBLISHER);

        assertThat(providerIds()).doesNotContain(provider);
        assertThat(loginVia(browser(), provider, "ivan", DIRECTORY_PASSWORD).statusCode())
                .isEqualTo(401);
        assertThat(host.status(id).orElseThrow().verified()).isFalse();
    }

    @Test
    void aProviderBeanTheDescriptorDoesNotDeclareRefusesActivation() throws Exception {
        String id = newPluginId();
        PluginJarBuilder undeclared = SignInPlugin.jar(id, false)
                .descriptorField("identityProviders", List.of(Map.of("id", id + ":other", "label", "Other")));
        Path jar = undeclared.build();

        assertThatThrownBy(() -> activate(jar))
                .hasStackTraceContaining("\"" + id + ":corp\" is not declared under identityProviders");
    }

    @Test
    void aDeclaredProviderWithoutABeanRefusesActivation() throws Exception {
        String id = newPluginId();
        Path jar = SignInPlugin.declare(new PluginJarBuilder(id), id + ":corp", "Corporate directory")
                .build();

        assertThatThrownBy(() -> activate(jar)).hasStackTraceContaining("\"" + id + ":corp\" is declared");
    }

    @Test
    void aStepUpThroughTheProviderSucceedsForTheSameAccountOnly() throws Exception {
        newAdministrator("judy");
        String id = installSignInPlugin(false);
        String provider = provider(id);
        var directory = SignInProbe.of(provider).add("uid=judy", "judy", DIRECTORY_PASSWORD, "eng");
        mapGroup(provider, "eng", Permissions.CLUSTER_READ);
        // The account is stored as judy@<provider>, which the provider is asked about as plain "judy".
        Browser judy = signedInVia(provider, "judy", DIRECTORY_PASSWORD);
        assertThat(account(provider, "uid=judy").getUsername()).isEqualTo("judy@" + provider);

        var ok = judy.post("/api/v1/auth/reauthenticate", "{\"password\":\"%s\"}".formatted(DIRECTORY_PASSWORD));
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat((String) JsonPath.read(ok.body(), "$.status")).isEqualTo("AUTHENTICATED");

        var wrong = judy.post("/api/v1/auth/reauthenticate", "{\"password\":\"nope\"}");
        assertThat(wrong.statusCode()).isEqualTo(403);

        // The directory now says "judy" is somebody else: the password is right, the account is not.
        directory.people.put(
                "judy",
                new SignInProbe.Person("uid=other", "judy-two", DIRECTORY_PASSWORD, null, java.util.Set.of("eng")));
        var other = judy.post("/api/v1/auth/reauthenticate", "{\"password\":\"%s\"}".formatted(DIRECTORY_PASSWORD));
        assertThat(other.statusCode()).isEqualTo(403);
    }

    private void activate(Path jar) throws Exception {
        try (JarFile file = new JarFile(jar.toFile())) {
            var descriptor =
                    descriptorParser.parse(file.getInputStream(file.getEntry("META-INF/artemis-studio/plugin.json"))
                            .readAllBytes());
            runtimeFactory
                    .activate(descriptor, jar, webContext.getServletContext())
                    .close();
        }
    }
}
