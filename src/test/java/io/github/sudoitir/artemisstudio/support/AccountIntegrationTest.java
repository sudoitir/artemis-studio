package io.github.sudoitir.artemisstudio.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.github.sudoitir.artemisstudio.kernel.security.Permissions;
import io.github.sudoitir.artemisstudio.kernel.security.ScopeIds;
import io.github.sudoitir.artemisstudio.kernel.security.SessionAuthentication;
import io.github.sudoitir.artemisstudio.kernel.security.SessionFacts;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RolePermissionEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.UserRoleEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.UserRoleRepository;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;

/**
 * Local accounts, second factors and sessions over real HTTP against the JDBC session store
 * (ADR-0143): the fixtures the account tests share. A {@link Browser} is a cookie jar with an address
 * of its own; users hold custom roles because the built-in administrator requires a second factor.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AccountIntegrationTest extends PostgresIntegrationTest {

    protected static final String PASSWORD = "correct-horse-battery";
    protected static final String AGENT = "Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0";
    protected static final String ME = "/api/v1/auth/me";
    /** Any endpoint that answers 200 to a fully signed-in user and refuses everyone else. */
    protected static final String CONSOLE = "/api/v1/auth/sessions";

    @LocalServerPort
    protected int port;

    @Autowired
    protected AppUserRepository users;

    @Autowired
    protected RoleRepository roles;

    @Autowired
    protected RolePermissionRepository rolePermissions;

    @Autowired
    protected UserRoleRepository userRoles;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    @Autowired
    protected FindByIndexNameSessionRepository<? extends Session> store;

    @Autowired
    protected JdbcClient jdbc;

    private static final AtomicInteger SOURCES = new AtomicInteger();

    /** What a user set up an authenticator app with. */
    public record Enrolled(String secret, List<String> recoveryCodes) {}

    /** A request that creates a key named {@code name} that reads clusters and expires in an hour. */
    protected static String mintBody(String name) {
        return "{\"name\":\"%s\",\"expiresAt\":\"%s\",\"grants\":[{\"action\":\"cluster:read\",\"scopeType\":\"GLOBAL\"}]}"
                .formatted(name, Instant.now().plus(Duration.ofHours(1)));
    }

    protected Browser browser() {
        int n = SOURCES.incrementAndGet();
        return new Browser(port, AGENT, "10.40." + n / 250 + "." + n % 250);
    }

    protected UUID newUser(String username) {
        AppUserEntity user =
                AppUserEntity.local(username, username + "@example.test", passwordEncoder.encode(PASSWORD));
        user.setMustChangePassword(false);
        return users.save(user).getId();
    }

    /** A custom role that requires a second factor and grants nothing else, held by the user. */
    protected void requireMfa(UUID userId) {
        RoleEntity role = new RoleEntity("mfa-" + UUID.randomUUID(), false);
        role.setRequiresMfa(true);
        role = roles.save(role);
        userRoles.save(new UserRoleEntity(userId, role.getId(), "GLOBAL", ScopeIds.GLOBAL));
    }

    /** A user who can do everything, through a role that does not require a second factor. */
    protected UUID newAdministrator(String username) {
        UUID id = newUser(username);
        UUID role =
                roles.save(new RoleEntity("admin-" + UUID.randomUUID(), false)).getId();
        rolePermissions.save(new RolePermissionEntity(role, Permissions.WILDCARD));
        userRoles.save(new UserRoleEntity(id, role, "GLOBAL", ScopeIds.GLOBAL));
        return id;
    }

    protected HttpResponse<String> login(Browser browser, String username) throws Exception {
        return browser.post(
                "/api/v1/auth/login", "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, PASSWORD));
    }

    /** A browser signed in with the password alone, for an account without a second factor. */
    protected Browser signedIn(String username) throws Exception {
        Browser browser = browser();
        assertThat((String) JsonPath.read(login(browser, username).body(), "$.status"))
                .isEqualTo("AUTHENTICATED");
        return browser;
    }

    /** Sets up an authenticator app for the user of this signed-in browser. */
    protected Enrolled enrol(Browser browser) throws Exception {
        var start = browser.post("/api/v1/auth/mfa/totp", null);
        assertThat(start.statusCode()).isEqualTo(200);
        String secret = JsonPath.read(start.body(), "$.secret");
        var confirm =
                browser.post("/api/v1/auth/mfa/totp/confirm", "{\"code\":\"%s\"}".formatted(TotpCodes.now(secret)));
        assertThat(confirm.statusCode()).isEqualTo(200);
        return new Enrolled(secret, JsonPath.read(confirm.body(), "$.recoveryCodes"));
    }

    protected Enrolled newEnrolledUser(String username) throws Exception {
        newUser(username);
        return enrol(signedIn(username));
    }

    /** A code nobody has used yet, as if 30 seconds had passed since the last one. */
    protected String freshCode(String username, Enrolled enrolled) {
        jdbc.sql("UPDATE local_totp SET last_step = 0 WHERE user_id = (SELECT id FROM app_user WHERE username = ?)")
                .param(username)
                .update();
        return TotpCodes.now(enrolled.secret());
    }

    protected HttpResponse<String> secondFactor(Browser browser, String field, String value) throws Exception {
        return browser.post("/api/v1/auth/second-factor", "{\"%s\":\"%s\"}".formatted(field, value));
    }

    /** A browser signed in with the password and an authenticator code. */
    protected Browser signedInWithSecondFactor(String username, Enrolled enrolled) throws Exception {
        Browser browser = browser();
        login(browser, username);
        var response = secondFactor(browser, "totpCode", freshCode(username, enrolled));
        assertThat((String) JsonPath.read(response.body(), "$.status")).isEqualTo("AUTHENTICATED");
        return browser;
    }

    protected static String problem(HttpResponse<String> response) {
        return JsonPath.read(response.body(), "$.type");
    }

    protected SessionFacts facts(String sessionId) {
        return store.findById(sessionId).getAttribute(SessionAuthentication.FACTS_ATTRIBUTE);
    }

    protected long audited(String action, String target) {
        return jdbc.sql("SELECT count(*) FROM audit_event WHERE action = ? AND target_name = ?")
                .params(action, target)
                .query(Long.class)
                .single();
    }

    /** Makes the browser's last sign-in older than the step-up window. */
    protected void makeStale(Browser browser) {
        stale(browser.sessionId());
    }

    private <S extends Session> void stale(String sessionId) {
        @SuppressWarnings("unchecked")
        FindByIndexNameSessionRepository<S> repository = (FindByIndexNameSessionRepository<S>) store;
        S session = repository.findById(sessionId);
        SessionFacts facts = session.getAttribute(SessionAuthentication.FACTS_ATTRIBUTE);
        session.setAttribute(
                SessionAuthentication.FACTS_ATTRIBUTE,
                facts.withAuthenticatedAt(Instant.now().minus(Duration.ofMinutes(10))));
        repository.save(session);
    }
}
