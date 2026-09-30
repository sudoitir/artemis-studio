package io.github.sudoitir.artemisstudio.kernel.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RolePermissionEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.UserRoleEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.UserRoleRepository;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsService;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;

/**
 * Session lifetimes and session management (ADR-0145) over real HTTP against the JDBC session store:
 * a browser here is a cookie jar, because MockMvc's mock sessions never reach the store the
 * endpoints read. The session's activity and facts are moved into the past directly in the store,
 * which is what time passing looks like to the filter.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionManagementIntegrationTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String ACTIVITY = "X-Studio-Activity";

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
    SettingsService settings;

    @Autowired
    JdbcClient jdbc;

    /** One browser: its own cookie jar and user agent. */
    private final class Browser {
        private final CookieManager cookies = new CookieManager();
        private final HttpClient http =
                HttpClient.newBuilder().cookieHandler(cookies).build();
        private final String userAgent;
        private final String host;

        Browser(String userAgent) {
            this(userAgent, "localhost");
        }

        Browser(String userAgent, String host) {
            this.userAgent = userAgent;
            this.host = host;
        }

        HttpResponse<String> send(String method, String path, String body, String... headers) throws Exception {
            String xsrf = cookies.getCookieStore().getCookies().stream()
                    .filter(c -> c.getName().equals("XSRF-TOKEN"))
                    .map(java.net.HttpCookie::getValue)
                    .findFirst()
                    .orElse("");
            var request = HttpRequest.newBuilder(URI.create("http://" + host + ":" + port + path))
                    .method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body))
                    .header("User-Agent", userAgent)
                    .header("X-XSRF-TOKEN", xsrf);
            if (body != null) {
                request.header("Content-Type", "application/json");
            }
            for (int i = 0; i < headers.length; i += 2) {
                request.header(headers[i], headers[i + 1]);
            }
            return http.send(request.build(), BodyHandlers.ofString());
        }

        int status(String method, String path, String... headers) throws Exception {
            return send(method, path, null, headers).statusCode();
        }

        Browser signIn(String username) throws Exception {
            send("GET", "/api/v1/auth/providers", null);
            var login = send(
                    "POST",
                    "/api/v1/auth/login",
                    "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, PASSWORD));
            assertThat(login.statusCode()).isEqualTo(200);
            return this;
        }

        List<String> handles() throws Exception {
            return JsonPath.read(send("GET", "/api/v1/auth/sessions", null).body(), "$[*].handle");
        }

        String currentHandle() throws Exception {
            List<String> current =
                    JsonPath.read(send("GET", "/api/v1/auth/sessions", null).body(), "$[?(@.current == true)].handle");
            assertThat(current).hasSize(1);
            return current.getFirst();
        }
    }

    private UUID newUser(String username) {
        AppUserEntity user =
                AppUserEntity.local(username, username + "@example.test", passwordEncoder.encode(PASSWORD));
        user.setMustChangePassword(false);
        return users.save(user).getId();
    }

    /** Holds every permission through a role of its own, because the built-in ADMIN role requires a second factor. */
    private UUID newAdministrator(String username) {
        UUID id = newUser(username);
        UUID role =
                roles.save(new RoleEntity("admin-" + UUID.randomUUID(), false)).getId();
        rolePermissions.save(new RolePermissionEntity(role, Permissions.WILDCARD));
        userRoles.save(new UserRoleEntity(id, role, "GLOBAL", ScopeIds.GLOBAL));
        return id;
    }

    private Browser signedIn(String username) throws Exception {
        return new Browser("Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0").signIn(username);
    }

    private static <S extends Session> void change(
            FindByIndexNameSessionRepository<S> repository, String username, Consumer<S> edit) {
        repository.findByPrincipalName(username).values().forEach(session -> {
            edit.accept(session);
            repository.save(session);
        });
    }

    private void lastActivity(String username, Instant at) {
        change(store, username, s -> s.setAttribute(SessionAuthentication.LAST_ACTIVITY_AT, at));
    }

    private Instant storedLastActivity(String username) {
        return store.findByPrincipalName(username)
                .values()
                .iterator()
                .next()
                .getAttribute(SessionAuthentication.LAST_ACTIVITY_AT);
    }

    private long audited(String action, String target, String actor) {
        return jdbc.sql("SELECT count(*) FROM audit_event WHERE action = ? AND target_name = ? AND username = ?")
                .params(action, target, actor)
                .query(Long.class)
                .single();
    }

    @Test
    void aClientOnIpv6LoopbackIsListedByItsCompactAddress() throws Exception {
        newUser("loopback-v6");
        Browser browser = new Browser("curl/8.5.0", "[::1]").signIn("loopback-v6");

        var addresses = JsonPath.<List<String>>read(
                browser.send("GET", "/api/v1/auth/sessions", null).body(), "$[*].clientAddress");

        assertThat(addresses).containsExactly("::1");
    }

    // ---- lifetimes ---------------------------------------------------------------------------

    @Test
    void aSessionWithNoUserActivityForLongerThanTheIdleTimeoutEnds() throws Exception {
        newUser("idle-ended");
        Browser browser = signedIn("idle-ended");
        assertThat(browser.status("GET", "/api/v1/auth/me")).isEqualTo(200);

        lastActivity("idle-ended", Instant.now().minus(Duration.ofMinutes(31)));

        assertThat(browser.status("GET", "/api/v1/auth/me")).isEqualTo(401);
        assertThat(store.findByPrincipalName("idle-ended")).isEmpty();
    }

    @Test
    void anActivityHeaderCannotReviveASessionThatAlreadyWentIdle() throws Exception {
        newUser("idle-revive");
        Browser browser = signedIn("idle-revive");

        lastActivity("idle-revive", Instant.now().minus(Duration.ofMinutes(31)));

        assertThat(browser.status("GET", "/api/v1/auth/me", ACTIVITY, "1")).isEqualTo(401);
    }

    @Test
    void pollingWithoutTheActivityHeaderDoesNotExtendASession() throws Exception {
        newUser("idle-polling");
        Browser browser = signedIn("idle-polling");
        Instant tenMinutesAgo = Instant.now().minus(Duration.ofMinutes(10));
        lastActivity("idle-polling", tenMinutesAgo);

        assertThat(browser.status("GET", "/api/v1/auth/me")).isEqualTo(200);
        assertThat(browser.status("GET", "/api/v1/auth/me", ACTIVITY, "0")).isEqualTo(200);

        assertThat(storedLastActivity("idle-polling")).isEqualTo(tenMinutesAgo);
    }

    @Test
    void aRequestWithTheActivityHeaderExtendsASession() throws Exception {
        newUser("idle-header");
        Browser browser = signedIn("idle-header");
        lastActivity("idle-header", Instant.now().minus(Duration.ofMinutes(20)));

        assertThat(browser.status("GET", "/api/v1/auth/me", ACTIVITY, "1")).isEqualTo(200);

        assertThat(storedLastActivity("idle-header")).isAfter(Instant.now().minus(Duration.ofMinutes(1)));
    }

    @Test
    void aRequestThatChangesSomethingExtendsASession() throws Exception {
        newUser("idle-mutation");
        Browser browser = signedIn("idle-mutation");
        lastActivity("idle-mutation", Instant.now().minus(Duration.ofMinutes(20)));

        assertThat(browser.status("DELETE", "/api/v1/auth/sessions")).isEqualTo(200);

        assertThat(storedLastActivity("idle-mutation")).isAfter(Instant.now().minus(Duration.ofMinutes(1)));
    }

    @Test
    void aSessionEndsAtTheAbsoluteLifetimeHoweverActiveItIs() throws Exception {
        newUser("absolute");
        Browser browser = signedIn("absolute");
        Instant now = Instant.now();
        change(store, "absolute", s -> {
            SessionFacts facts = s.getAttribute(SessionAuthentication.FACTS_ATTRIBUTE);
            s.setAttribute(
                    SessionAuthentication.FACTS_ATTRIBUTE,
                    new SessionFacts(
                            facts.authenticatedAt(),
                            null,
                            null,
                            now.minus(Duration.ofHours(12)).minusSeconds(60),
                            facts.clientAddress(),
                            facts.userAgent()));
            s.setAttribute(SessionAuthentication.LAST_ACTIVITY_AT, now);
        });

        assertThat(browser.status("GET", "/api/v1/auth/me", ACTIVITY, "1")).isEqualTo(401);
        assertThat(store.findByPrincipalName("absolute")).isEmpty();
    }

    @Test
    void changingThePasswordDoesNotMakeAnActiveSessionLookIdle() throws Exception {
        newUser("idle-password");
        Browser browser = signedIn("idle-password");
        Instant now = Instant.now();
        change(store, "idle-password", s -> {
            SessionFacts facts = s.getAttribute(SessionAuthentication.FACTS_ATTRIBUTE);
            s.setAttribute(
                    SessionAuthentication.FACTS_ATTRIBUTE,
                    new SessionFacts(
                            facts.authenticatedAt(),
                            null,
                            null,
                            now.minus(Duration.ofHours(2)),
                            facts.clientAddress(),
                            facts.userAgent()));
            s.setAttribute(SessionAuthentication.LAST_ACTIVITY_AT, now);
        });

        var changed = browser.send(
                "POST",
                "/api/v1/auth/password",
                "{\"currentPassword\":\"%s\",\"newPassword\":\"Tr0ub4dor&3-horse-staple\"}".formatted(PASSWORD));

        assertThat(changed.statusCode()).isEqualTo(204);
        assertThat(browser.status("GET", "/api/v1/auth/me")).isEqualTo(200);
    }

    @Test
    void theStorageTimeoutIsTheIdleTimeoutAsABackstop() throws Exception {
        newUser("backstop");
        signedIn("backstop");

        assertThat(store.findByPrincipalName("backstop").values())
                .singleElement()
                .satisfies(s -> assertThat(s.getMaxInactiveInterval()).isEqualTo(Duration.ofMinutes(30)));
    }

    @Test
    void changingTheIdleSettingTakesEffectAtOnce() throws Exception {
        newUser("idle-setting");
        Browser browser = signedIn("idle-setting");
        lastActivity("idle-setting", Instant.now().minus(Duration.ofMinutes(5)));
        var admin = new StudioPrincipal(
                UUID.randomUUID(),
                "idle-setting-admin",
                Set.of(new Grant(Grant.ScopeType.GLOBAL, ScopeIds.GLOBAL, Set.of(SettingsPermissions.SETTINGS_WRITE))),
                false);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(admin, null, admin.getAuthorities()));
        SecurityContextHolder.setContext(context);
        try {
            settings.put(SessionLifetimes.IDLE_TIMEOUT, "2m");
            try {
                assertThat(browser.status("GET", "/api/v1/auth/me")).isEqualTo(401);
            } finally {
                settings.reset(SessionLifetimes.IDLE_TIMEOUT);
            }
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    // ---- own sessions ------------------------------------------------------------------------

    @Test
    void aUserSeesTheirSessionsAndWhichOneIsCurrentWithoutTheIdentifier() throws Exception {
        newUser("own-list");
        Browser laptop = signedIn("own-list");
        Browser phone = new Browser("curl/8.5.0").signIn("own-list");

        var body = laptop.send("GET", "/api/v1/auth/sessions", null).body();

        assertThat((List<?>) JsonPath.read(body, "$")).hasSize(2);
        assertThat((List<?>) JsonPath.read(body, "$[?(@.current == true)]")).hasSize(1);
        assertThat((List<String>) JsonPath.read(body, "$[?(@.current == true)].userAgent"))
                .singleElement()
                .asString()
                .contains("Firefox");
        assertThat((List<String>) JsonPath.read(body, "$[?(@.current == false)].userAgent"))
                .containsExactly("curl/8.5.0");
        assertThat((List<String>) JsonPath.read(body, "$[*].handle")).allMatch(h -> h.matches("[0-9a-f]{32}"));
        assertThat((List<String>) JsonPath.read(body, "$[*].clientAddress")).doesNotContainNull();
        store.findByPrincipalName("own-list")
                .keySet()
                .forEach(id -> assertThat(body).doesNotContain(id));
        assertThat(phone.currentHandle()).isNotEqualTo(laptop.currentHandle());
    }

    @Test
    void endingAnotherSessionRejectsItAtItsNextRequestAndIsAudited() throws Exception {
        newUser("own-end");
        Browser laptop = signedIn("own-end");
        Browser phone = signedIn("own-end");
        String phoneHandle = phone.currentHandle();

        assertThat(laptop.status("DELETE", "/api/v1/auth/sessions/" + phoneHandle))
                .isEqualTo(204);

        assertThat(phone.status("GET", "/api/v1/auth/me")).isEqualTo(401);
        assertThat(laptop.status("GET", "/api/v1/auth/me")).isEqualTo(200);
        assertThat(laptop.handles()).hasSize(1);
        assertThat(audited("SESSION_END", "own-end", "own-end")).isEqualTo(1);
    }

    @Test
    void endingTheCurrentSessionIsSigningOut() throws Exception {
        newUser("own-current");
        Browser laptop = signedIn("own-current");

        assertThat(laptop.status("DELETE", "/api/v1/auth/sessions/" + laptop.currentHandle()))
                .isEqualTo(204);

        assertThat(laptop.status("GET", "/api/v1/auth/me")).isEqualTo(401);
        assertThat(store.findByPrincipalName("own-current")).isEmpty();
    }

    @Test
    void aUserCannotEndAnotherUsersSession() throws Exception {
        newUser("own-victim");
        newUser("own-attacker");
        Browser victim = signedIn("own-victim");
        Browser attacker = signedIn("own-attacker");

        assertThat(attacker.status("DELETE", "/api/v1/auth/sessions/" + victim.currentHandle()))
                .isEqualTo(404);
        assertThat(attacker.status("DELETE", "/api/v1/auth/sessions/" + "0".repeat(32)))
                .isEqualTo(404);

        assertThat(victim.status("GET", "/api/v1/auth/me")).isEqualTo(200);
    }

    @Test
    void signingOutEverywhereElseKeepsTheCurrentSession() throws Exception {
        newUser("own-others");
        Browser laptop = signedIn("own-others");
        Browser phone = signedIn("own-others");
        Browser tablet = signedIn("own-others");

        var response = laptop.send("DELETE", "/api/v1/auth/sessions", null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(response.body(), "$.ended")).isEqualTo(2);
        assertThat(phone.status("GET", "/api/v1/auth/me")).isEqualTo(401);
        assertThat(tablet.status("GET", "/api/v1/auth/me")).isEqualTo(401);
        assertThat(laptop.status("GET", "/api/v1/auth/me")).isEqualTo(200);
        assertThat(audited("SESSION_END", "own-others", "own-others")).isEqualTo(2);
    }

    // ---- administrators ----------------------------------------------------------------------

    @Test
    void anAdministratorListsAndEndsAnotherUsersSessionsAndItIsAudited() throws Exception {
        newAdministrator("adm-admin");
        UUID targetId = newUser("adm-target");
        Browser admin = signedIn("adm-admin");
        Browser target = signedIn("adm-target");
        Browser other = signedIn("adm-target");
        String targetHandle = target.currentHandle();

        var listed = admin.send("GET", "/api/v1/users/" + targetId + "/sessions", null);
        assertThat(listed.statusCode()).isEqualTo(200);
        assertThat((List<?>) JsonPath.read(listed.body(), "$")).hasSize(2);
        assertThat((List<?>) JsonPath.read(listed.body(), "$[?(@.current == true)]"))
                .isEmpty();

        assertThat(admin.status("DELETE", "/api/v1/users/" + targetId + "/sessions/" + targetHandle))
                .isEqualTo(204);
        assertThat(target.status("GET", "/api/v1/auth/me")).isEqualTo(401);
        assertThat(other.status("GET", "/api/v1/auth/me")).isEqualTo(200);
        assertThat(audited("SESSION_END", "adm-target", "adm-admin")).isEqualTo(1);

        var all = admin.send("DELETE", "/api/v1/users/" + targetId + "/sessions", null);
        assertThat((Integer) JsonPath.read(all.body(), "$.ended")).isEqualTo(1);
        assertThat(other.status("GET", "/api/v1/auth/me")).isEqualTo(401);
        assertThat(admin.status("GET", "/api/v1/auth/me")).isEqualTo(200);
    }

    @Test
    void withoutUserAdministrationTheAdminEndpointsAreRefused() throws Exception {
        UUID targetId = newUser("adm-denied-target");
        newUser("adm-denied");
        Browser target = signedIn("adm-denied-target");
        Browser caller = signedIn("adm-denied");

        assertThat(caller.status("GET", "/api/v1/users/" + targetId + "/sessions"))
                .isEqualTo(403);
        assertThat(caller.status("DELETE", "/api/v1/users/" + targetId + "/sessions/" + target.currentHandle()))
                .isEqualTo(403);
        assertThat(caller.status("DELETE", "/api/v1/users/" + targetId + "/sessions"))
                .isEqualTo(403);

        assertThat(target.status("GET", "/api/v1/auth/me")).isEqualTo(200);
    }
}
