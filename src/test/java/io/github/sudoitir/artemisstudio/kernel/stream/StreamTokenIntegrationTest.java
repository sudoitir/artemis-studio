package io.github.sudoitir.artemisstudio.kernel.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
import io.github.sudoitir.artemisstudio.support.AccountIntegrationTest;
import io.github.sudoitir.artemisstudio.support.Browser;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A stream opened with an API token lives no longer than the token is accepted (ADR-0143), over real
 * HTTP. The periodic session check also asks whether each token stream's token is still live, so a
 * revoked token, or one whose owner now requires a second factor it was minted without, ends its stream
 * within the check's interval.
 */
class StreamTokenIntegrationTest extends AccountIntegrationTest {

    @Autowired
    SseHub hub;

    /** Held for the life of the test: an unreferenced client may close the streams it opened. */
    private final HttpClient http = HttpClient.newHttpClient();

    private static String mintRequest(String name) {
        return mintBody(name);
    }

    private String mint(Browser browser, String name) throws Exception {
        return JsonPath.read(browser.post("/api/v1/tokens", mintRequest(name)).body(), "$.value");
    }

    /** Opens the event stream with a token; completes when the server ends it. */
    private CompletableFuture<Void> openStream(String token, UUID clusterId) throws Exception {
        var request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/stream?clusterId=" + clusterId))
                .header("Authorization", "Bearer " + token)
                .build();
        var pending = http.sendAsync(request, BodyHandlers.ofInputStream());
        await("the stream subscribes")
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(50))
                .untilAsserted(() -> assertThat(hub.subscriberCount(clusterId)).isEqualTo(1));
        var response = pending.get(10, TimeUnit.SECONDS);
        assertThat(response.statusCode()).isEqualTo(200);
        return CompletableFuture.runAsync(() -> {
            try (var in = response.body()) {
                in.transferTo(OutputStream.nullOutputStream());
            } catch (IOException _) {
                // the server closed it
            }
        });
    }

    @Test
    void aStreamOpenedWithATokenEndsWhenTheTokenIsRevoked() throws Exception {
        newAdministrator("tk-stream-revoked");
        Browser owner = signedIn("tk-stream-revoked");
        var created = owner.post("/api/v1/tokens", mintRequest("stream"));
        String token = JsonPath.read(created.body(), "$.value");
        String tokenId = JsonPath.read(created.body(), "$.token.id");
        UUID cluster = UUID.randomUUID();
        var stream = openStream(token, cluster);
        assertThat(stream.isDone()).isFalse();

        assertThat(owner.send("DELETE", "/api/v1/tokens/" + tokenId, null).statusCode())
                .isEqualTo(204);

        stream.get(30, TimeUnit.SECONDS);
        assertThat(hub.subscriberCount(cluster)).isZero();
    }

    @Test
    void aStreamOpenedWithATokenEndsWhenItsOwnerNowRequiresASecondFactorItLacks() throws Exception {
        UUID id = newAdministrator("tk-stream-required");
        String token = mint(signedIn("tk-stream-required"), "stream");
        UUID cluster = UUID.randomUUID();
        var stream = openStream(token, cluster);

        requireMfa(id);

        stream.get(30, TimeUnit.SECONDS);
        assertThat(hub.subscriberCount(cluster)).isZero();
    }

    @Test
    void aStreamOpenedWithATokenStaysOpenWhileTheTokenIsAccepted() throws Exception {
        newAdministrator("tk-stream-fine");
        String token = mint(signedIn("tk-stream-fine"), "stream");
        UUID cluster = UUID.randomUUID();
        var stream = openStream(token, cluster);

        // Two checks of the periodic job pass without it ending.
        await("the stream outlives the checks")
                .during(Duration.ofSeconds(22))
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofSeconds(1))
                .until(() -> !stream.isDone());

        assertThat(hub.subscriberCount(cluster)).isEqualTo(1);
        hub.closeAll();
    }
}
