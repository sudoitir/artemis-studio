package io.github.sudoitir.artemisstudio.kernel.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jayway.jsonpath.JsonPath;
import io.github.sudoitir.artemisstudio.kernel.security.Permissions;
import io.github.sudoitir.artemisstudio.kernel.security.SessionAuthentication;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RolePermissionEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.UserRoleEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.UserRoleRepository;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.io.IOException;
import java.io.OutputStream;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;

/**
 * An event stream lives no longer than the session that opened it, over real HTTP against the JDBC
 * session store (MockMvc's mock sessions never reach it). A browser is a cookie jar holding an open
 * stream; a stream has ended when the server closes the response.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StreamSessionIntegrationTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final Duration PROMPT = Duration.ofSeconds(5);

    @LocalServerPort
    int port;

    @Autowired
    AppUserRepository users;

    @Autowired
    RoleRepository roles;

    @Autowired
    RolePermissionRepository rolePermissions;

    @Autowired
    UserRoleRepository userRoles;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    FindByIndexNameSessionRepository<? extends Session> store;

    @Autowired
    SseHub hub;

    @Autowired
    SessionAuthentication sessions;

    private final class Browser {
        final CookieManager cookies = new CookieManager();
        final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();

        Browser signIn(String username) throws Exception {
            send("GET", "/api/v1/auth/providers");
            int status = send(
                            "POST",
                            "/api/v1/auth/login",
                            "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, PASSWORD))
                    .statusCode();
            assertThat(status).isEqualTo(200);
            return this;
        }

        HttpResponse<String> send(String method, String path) throws Exception {
            return send(method, path, null);
        }

        HttpResponse<String> send(String method, String path, String body) throws Exception {
            String xsrf = cookies.getCookieStore().getCookies().stream()
                    .filter(c -> c.getName().equals("XSRF-TOKEN"))
                    .map(HttpCookie::getValue)
                    .findFirst()
                    .orElse("");
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .method(
                            method,
                            body == null
                                    ? HttpRequest.BodyPublishers.noBody()
                                    : HttpRequest.BodyPublishers.ofString(body))
                    .header("X-XSRF-TOKEN", xsrf);
            if (body != null) {
                request.header("Content-Type", "application/json");
            }
            return http.send(request.build(), BodyHandlers.ofString());
        }

        /** Opens the event stream, which answers at once; completes when the server ends it. */
        CompletableFuture<Void> openStream(UUID clusterId) throws Exception {
            var request = HttpRequest.newBuilder(
                            URI.create("http://localhost:" + port + "/api/v1/stream?clusterId=" + clusterId))
                    .build();
            var pending = http.sendAsync(request, BodyHandlers.ofInputStream());
            awaitSubscribed(clusterId, 1);
            var response = pending.get(10, TimeUnit.SECONDS);
            assertThat(response.statusCode()).isEqualTo(200);
            return CompletableFuture.runAsync(() -> {
                try (var in = response.body()) {
                    in.transferTo(OutputStream.nullOutputStream());
                } catch (IOException e) {
                    // the server closed it
                }
            });
        }

        String currentHandle() throws Exception {
            java.util.List<String> current =
                    JsonPath.read(send("GET", "/api/v1/auth/sessions").body(), "$[?(@.current == true)].handle");
            return current.getFirst();
        }
    }

    private void newUser(String username, boolean administrator) {
        AppUserEntity user =
                AppUserEntity.local(username, username + "@example.test", passwordEncoder.encode(PASSWORD));
        user.setMustChangePassword(false);
        UUID id = users.save(user).getId();
        if (administrator) {
            // A role of its own holding everything, because the built-in ADMIN role requires a second factor.
            UUID role = roles.save(new RoleEntity("admin-" + UUID.randomUUID(), false))
                    .getId();
            rolePermissions.save(new RolePermissionEntity(role, Permissions.WILDCARD));
            userRoles.save(new UserRoleEntity(
                    id, role, "GLOBAL", io.github.sudoitir.artemisstudio.kernel.security.ScopeIds.GLOBAL));
        }
    }

    private void awaitSubscribed(UUID clusterId, int count) throws InterruptedException {
        for (int i = 0; i < 100 && hub.subscriberCount(clusterId) != count; i++) {
            Thread.sleep(50);
        }
        assertThat(hub.subscriberCount(clusterId)).isEqualTo(count);
    }

    private static void assertEndsWithin(CompletableFuture<Void> stream, Duration within) throws Exception {
        stream.get(within.toSeconds(), TimeUnit.SECONDS);
    }

    @Test
    void signingOutEndsTheStream() throws Exception {
        newUser("stream-logout", true);
        UUID cluster = UUID.randomUUID();
        Browser browser = new Browser().signIn("stream-logout");
        var stream = browser.openStream(cluster);

        assertThat(browser.send("POST", "/api/v1/auth/logout").statusCode()).isEqualTo(204);

        assertEndsWithin(stream, PROMPT);
        assertThat(hub.subscriberCount(cluster)).isZero();
    }

    @Test
    void endingASessionFromAnotherSessionEndsItsStream() throws Exception {
        newUser("stream-ended-elsewhere", true);
        UUID cluster = UUID.randomUUID();
        Browser phone = new Browser().signIn("stream-ended-elsewhere");
        var stream = phone.openStream(cluster);
        Browser laptop = new Browser().signIn("stream-ended-elsewhere");

        assertThat(laptop.send("DELETE", "/api/v1/auth/sessions/" + phone.currentHandle())
                        .statusCode())
                .isEqualTo(204);

        assertEndsWithin(stream, PROMPT);
    }

    @Test
    void aStreamOfASessionThatWentIdleEndsAtTheNextCheck() throws Exception {
        newUser("stream-idle", true);
        UUID cluster = UUID.randomUUID();
        Browser browser = new Browser().signIn("stream-idle");
        var stream = browser.openStream(cluster);

        change(
                "stream-idle",
                s -> s.setAttribute(
                        SessionAuthentication.LAST_ACTIVITY_AT, Instant.now().minus(Duration.ofMinutes(31))));

        // The scheduled check (every 10 seconds) finds it; the open stream never counted as activity.
        assertEndsWithin(stream, StreamJobs.SESSION_CHECK_INTERVAL.plusSeconds(15));
    }

    @Test
    void aStreamOfASessionRemovedFromTheStoreEndsAtTheNextCheck() throws Exception {
        newUser("stream-removed", true);
        UUID cluster = UUID.randomUUID();
        Browser browser = new Browser().signIn("stream-removed");
        var stream = browser.openStream(cluster);

        // What ending it on another instance looks like here: no event, only the store changed.
        store.findByPrincipalName("stream-removed").keySet().forEach(store::deleteById);

        assertEndsWithin(stream, StreamJobs.SESSION_CHECK_INTERVAL.plusSeconds(15));
    }

    @Test
    void aStreamOfALiveSessionSurvivesTheCheck() throws Exception {
        newUser("stream-live", true);
        UUID cluster = UUID.randomUUID();
        Browser browser = new Browser().signIn("stream-live");
        var stream = browser.openStream(cluster);

        hub.closeEndedSessions(sessions::isLive);

        assertThatThrownBy(() -> stream.get(1, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);
        assertThat(hub.subscriberCount(cluster)).isEqualTo(1);
        hub.closeAll();
    }

    private <S extends Session> void change(String username, java.util.function.Consumer<Session> edit) {
        changeIn(store, username, edit);
    }

    private static <S extends Session> void changeIn(
            FindByIndexNameSessionRepository<S> repository,
            String username,
            java.util.function.Consumer<Session> edit) {
        repository.findByPrincipalName(username).values().forEach(session -> {
            edit.accept(session);
            repository.save(session);
        });
    }
}
