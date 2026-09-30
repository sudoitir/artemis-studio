package io.github.sudoitir.artemisstudio.feature.identitylocal.mfa;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.github.sudoitir.artemisstudio.kernel.security.ScopeIds;
import io.github.sudoitir.artemisstudio.kernel.security.SessionAuthentication;
import io.github.sudoitir.artemisstudio.kernel.security.SessionFacts;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.UserRoleEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.UserRoleRepository;
import io.github.sudoitir.artemisstudio.support.Browser;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import io.github.sudoitir.artemisstudio.support.TotpCodes;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Passkeys, end to end over real HTTP (ADR-0142): registering and signing in with a software
 * authenticator from webauthn4j's own test support, so Spring Security's relying party verifies a
 * genuine attestation and a genuine assertion. Studio's public address is set, which is what makes
 * passkeys available; the case without it is in {@link SecondFactorLoginIntegrationTest}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "artemis-studio.public-url=" + PasskeyIntegrationTest.ORIGIN)
class PasskeyIntegrationTest extends PostgresIntegrationTest {

    static final String ORIGIN = "https://studio.example.test";
    private static final String PASSWORD = "correct-horse-battery";
    private static final String AGENT = "Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0";
    private static final String ME = "/api/v1/auth/me";
    private static final String CONSOLE = "/api/v1/auth/sessions";

    @LocalServerPort
    int port;

    @Autowired
    AppUserRepository users;

    @Autowired
    RoleRepository roles;

    @Autowired
    UserRoleRepository userRoles;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    FindByIndexNameSessionRepository<? extends Session> store;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    private final AtomicInteger sources = new AtomicInteger();

    // ---- fixtures ----------------------------------------------------------------------------

    private Browser browser() {
        int n = sources.incrementAndGet();
        return new Browser(port, AGENT, "10.30." + n / 250 + "." + n % 250);
    }

    private UUID newUser(String username) {
        AppUserEntity user =
                AppUserEntity.local(username, username + "@example.test", passwordEncoder.encode(PASSWORD));
        user.setMustChangePassword(false);
        return users.save(user).getId();
    }

    private void requireMfa(UUID userId) {
        RoleEntity role = new RoleEntity("mfa-" + UUID.randomUUID(), false);
        role.setRequiresMfa(true);
        role = roles.save(role);
        userRoles.save(new UserRoleEntity(userId, role.getId(), "GLOBAL", ScopeIds.GLOBAL));
    }

    private HttpResponse<String> login(Browser browser, String username) throws Exception {
        return browser.post(
                "/api/v1/auth/login", "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, PASSWORD));
    }

    private Browser signedIn(String username) throws Exception {
        Browser browser = browser();
        assertThat((String) JsonPath.read(login(browser, username).body(), "$.status"))
                .isEqualTo("AUTHENTICATED");
        return browser;
    }

    private HttpResponse<String> register(Browser browser, PasskeyDevice device, String label) throws Exception {
        var options = browser.post("/api/v1/auth/mfa/webauthn/options", null);
        assertThat(options.statusCode()).as(options.body()).isEqualTo(200);
        return browser.post(
                "/api/v1/auth/mfa/webauthn",
                "{\"label\":\"%s\",\"credential\":%s}".formatted(label, device.create(options.body())));
    }

    /** Signs a user with one passkey in, from a fresh browser, and returns the device that holds it. */
    private PasskeyDevice newUserWithAPasskey(String username) throws Exception {
        newUser(username);
        PasskeyDevice device = new PasskeyDevice(ORIGIN);
        assertThat(register(signedIn(username), device, "Laptop").statusCode()).isEqualTo(200);
        return device;
    }

    private HttpResponse<String> answer(Browser browser, PasskeyDevice device) throws Exception {
        var options = browser.post("/api/v1/auth/second-factor/options", null);
        assertThat(options.statusCode()).as(options.body()).isEqualTo(200);
        return browser.post("/api/v1/auth/second-factor", "{\"webauthn\":%s}".formatted(device.get(options.body())));
    }

    private static String problem(HttpResponse<String> response) {
        return JsonPath.read(response.body(), "$.type");
    }

    private SessionFacts facts(String sessionId) {
        return store.findById(sessionId).getAttribute(SessionAuthentication.FACTS);
    }

    private long audited(String action, String target) {
        return jdbc.sql("SELECT count(*) FROM audit_event WHERE action = ? AND target_name = ?")
                .params(action, target)
                .query(Long.class)
                .single();
    }

    private void makeStale(Browser browser) {
        change(browser.sessionId());
    }

    private <S extends Session> void change(String sessionId) {
        @SuppressWarnings("unchecked")
        FindByIndexNameSessionRepository<S> repository = (FindByIndexNameSessionRepository<S>) store;
        S session = repository.findById(sessionId);
        SessionFacts facts = session.getAttribute(SessionAuthentication.FACTS);
        session.setAttribute(
                SessionAuthentication.FACTS,
                facts.withAuthenticatedAt(Instant.now().minus(Duration.ofMinutes(10))));
        repository.save(session);
    }

    // ---- enrolment ---------------------------------------------------------------------------

    @Test
    void deletingAUserDeletesTheirPasskeysToo() throws Exception {
        newUserWithAPasskey("pk-deleted");
        assertThat(jdbc.sql("SELECT count(*) FROM user_credentials")
                        .query(Long.class)
                        .single())
                .isPositive();

        jdbc.sql("DELETE FROM app_user WHERE username = ?").param("pk-deleted").update();

        assertThat(jdbc.sql("SELECT count(*) FROM user_entities WHERE display_name = ?")
                        .param("pk-deleted")
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM user_credentials WHERE label = 'Laptop'"
                                + " AND user_entity_user_id NOT IN (SELECT id FROM user_entities)")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    /**
     * Two sessions of one user enrol their first factor at once, an authenticator app and a passkey. Each
     * reads that the account has no factor yet, and each would issue recovery codes, so the second set
     * replaces the first and one browser shows codes that no longer work. The confirmation is held halfway,
     * after it has looked, by locking the pending secret's row while the registration runs.
     */
    @Test
    void enrollingTheFirstFactorTwiceAtOnceIssuesOneSetOfRecoveryCodes() throws Exception {
        UUID id = newUser("pk-race");
        Browser app = signedIn("pk-race");
        Browser key = signedIn("pk-race");
        String secret = JsonPath.read(app.post("/api/v1/auth/mfa/totp", null).body(), "$.secret");
        String creation = new PasskeyDevice(ORIGIN)
                .create(key.post("/api/v1/auth/mfa/webauthn/options", null).body());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<HttpResponse<String>>> responses = new java.util.ArrayList<>();
        try {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.sql("SELECT user_id FROM local_totp_pending WHERE user_id = ? FOR UPDATE")
                        .param(id)
                        .query(UUID.class)
                        .single();
                responses.add(pool.submit(() -> app.post(
                        "/api/v1/auth/mfa/totp/confirm", "{\"code\":\"%s\"}".formatted(TotpCodes.now(secret)))));
                awaitALockWait();
                responses.add(pool.submit(() -> key.post(
                        "/api/v1/auth/mfa/webauthn", "{\"label\":\"Laptop\",\"credential\":%s}".formatted(creation))));
                try {
                    responses.get(1).get(3, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    // it waits for the confirmation, which is what it should do
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            var confirmed = responses.get(0).get(10, TimeUnit.SECONDS);
            var registered = responses.get(1).get(10, TimeUnit.SECONDS);

            assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
            assertThat(registered.statusCode()).as(registered.body()).isEqualTo(200);
            assertThat(java.util.stream.Stream.of(confirmed, registered)
                            .filter(r -> JsonPath.read(r.body(), "$.recoveryCodes") != null))
                    .as("the sessions that were shown recovery codes")
                    .hasSize(1);
            assertThat(jdbc.sql("SELECT count(*) FROM local_recovery_code WHERE user_id = ? AND used_at IS NULL")
                            .param(id)
                            .query(Long.class)
                            .single())
                    .isEqualTo(10);
        } finally {
            pool.shutdownNow();
        }
    }

    private void awaitALockWait() {
        for (int i = 0; i < 100; i++) {
            if (jdbc.sql("SELECT count(*) FROM pg_locks WHERE NOT granted")
                            .query(Long.class)
                            .single()
                    > 0) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("nothing is waiting for a lock");
    }

    @Test
    void aPasskeyIsRegisteredListedAndGivesTheFirstFactorItsRecoveryCodes() throws Exception {
        newUser("pk-enrol");
        Browser browser = signedIn("pk-enrol");

        var registered = register(browser, new PasskeyDevice(ORIGIN), "Work laptop");

        assertThat(registered.statusCode()).as(registered.body()).isEqualTo(200);
        assertThat((List<String>) JsonPath.read(registered.body(), "$.recoveryCodes"))
                .hasSize(10);
        assertThat((String) JsonPath.read(registered.body(), "$.passkey.label")).isEqualTo("Work laptop");
        assertThat((String) JsonPath.read(registered.body(), "$.passkey.id")).isNotBlank();
        var status = browser.send("GET", "/api/v1/auth/mfa", null).body();
        assertThat((Boolean) JsonPath.read(status, "$.webauthn.available")).isTrue();
        assertThat((Object) JsonPath.read(status, "$.webauthn.reason")).isNull();
        assertThat((Boolean) JsonPath.read(status, "$.enrolled")).isTrue();
        assertThat((Boolean) JsonPath.read(status, "$.totpEnrolled")).isFalse();
        assertThat((List<String>) JsonPath.read(status, "$.passkeys[*].label")).containsExactly("Work laptop");
        assertThat(facts(browser.sessionId()).mfaMethod()).isEqualTo(SessionFacts.Method.WEBAUTHN);
        assertThat(audited("MFA_ENROL", "pk-enrol")).isEqualTo(1);
        // The passkey is stored under the account's id, and its owner's name is shown, not the id.
        UUID id = users.findByUsername("pk-enrol").orElseThrow().getId();
        assertThat(jdbc.sql("SELECT display_name FROM user_entities WHERE name = ?")
                        .param(id.toString())
                        .query(String.class)
                        .single())
                .isEqualTo("pk-enrol");
    }

    @Test
    void aSecondPasskeyGetsNoRecoveryCodesAndNeedsAStepUp() throws Exception {
        PasskeyDevice first = newUserWithAPasskey("pk-second");
        Browser browser = browser();
        login(browser, "pk-second");
        assertThat(answer(browser, first).statusCode()).isEqualTo(200);
        makeStale(browser);

        var refused = browser.post("/api/v1/auth/mfa/webauthn/options", null);
        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(problem(refused)).endsWith("/reauthentication-required");

        browser.post("/api/v1/auth/reauthenticate", "{\"password\":\"%s\"}".formatted(PASSWORD));
        assertThat(answer(browser, first).statusCode()).isEqualTo(200);
        var registered = register(browser, new PasskeyDevice(ORIGIN), "Phone");

        assertThat(registered.statusCode()).as(registered.body()).isEqualTo(200);
        assertThat((Object) JsonPath.read(registered.body(), "$.recoveryCodes")).isNull();
        assertThat((List<String>) JsonPath.read(
                        browser.send("GET", "/api/v1/auth/mfa", null).body(), "$.passkeys[*].label"))
                .containsExactly("Laptop", "Phone");
    }

    @Test
    void aPasskeyLiftsTheEnrolmentRestrictionLikeAnAuthenticatorApp() throws Exception {
        UUID id = newUser("pk-required");
        requireMfa(id);
        Browser browser = signedIn("pk-required");
        assertThat(browser.status("GET", CONSOLE)).isEqualTo(423);

        var registered = register(browser, new PasskeyDevice(ORIGIN), "Key");

        assertThat(registered.statusCode()).isEqualTo(200);
        assertThat(browser.status("GET", CONSOLE)).isEqualTo(200);
        assertThat((Boolean) JsonPath.read(browser.send("GET", ME, null).body(), "$.secondFactorEnrolmentRequired"))
                .isFalse();
        assertThat(facts(browser.sessionId()).mfaVerifiedAt()).isNotNull();
    }

    @Test
    void anAnswerFromAnotherOriginIsNotAPasskeyOfStudio() throws Exception {
        newUser("pk-origin");
        Browser browser = signedIn("pk-origin");

        var registered = register(browser, new PasskeyDevice("https://phishing.example.test"), "Key");

        assertThat(registered.statusCode()).isEqualTo(400);
        assertThat((String) JsonPath.read(registered.body(), "$.detail")).contains("could not be verified");
        assertThat((Boolean) JsonPath.read(
                        browser.send("GET", "/api/v1/auth/mfa", null).body(), "$.enrolled"))
                .isFalse();
    }

    @Test
    void theOptionsAreSpentByTheFirstAnswer() throws Exception {
        newUser("pk-replay");
        Browser browser = signedIn("pk-replay");
        PasskeyDevice device = new PasskeyDevice(ORIGIN);
        var options = browser.post("/api/v1/auth/mfa/webauthn/options", null);
        String credential = device.create(options.body());
        String body = "{\"label\":\"Key\",\"credential\":%s}".formatted(credential);

        assertThat(browser.post("/api/v1/auth/mfa/webauthn", body).statusCode()).isEqualTo(200);

        var again = browser.post("/api/v1/auth/mfa/webauthn", body);
        assertThat(again.statusCode()).isEqualTo(400);
        assertThat((String) JsonPath.read(again.body(), "$.detail")).contains("expired");
    }

    // ---- sign-in -----------------------------------------------------------------------------

    @Test
    void aPasskeyCompletesTheSignInAndRecordsItsUse() throws Exception {
        PasskeyDevice device = newUserWithAPasskey("pk-login");
        Browser browser = browser();
        var password = login(browser, "pk-login");
        assertThat((String) JsonPath.read(password.body(), "$.status")).isEqualTo("SECOND_FACTOR_REQUIRED");
        assertThat((List<String>) JsonPath.read(password.body(), "$.methods"))
                .containsExactly("WEBAUTHN", "RECOVERY_CODE");
        assertThat(browser.status("GET", ME)).isEqualTo(401);

        var response = answer(browser, device);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat((String) JsonPath.read(response.body(), "$.status")).isEqualTo("AUTHENTICATED");
        assertThat((String) JsonPath.read(response.body(), "$.me.username")).isEqualTo("pk-login");
        assertThat(browser.status("GET", CONSOLE)).isEqualTo(200);
        SessionFacts facts = facts(browser.sessionId());
        assertThat(facts.mfaMethod()).isEqualTo(SessionFacts.Method.WEBAUTHN);
        assertThat(facts.authenticatedAt()).isNotNull();
        var passkey = browser.send("GET", "/api/v1/auth/mfa", null).body();
        assertThat(Instant.parse(JsonPath.read(passkey, "$.passkeys[0].lastUsed")))
                .isAfter(Instant.parse(JsonPath.read(passkey, "$.passkeys[0].created")));
        assertThat(audited("SECOND_FACTOR", "pk-login")).isEqualTo(1);
    }

    @Test
    void aChallengeAnswersOnceEvenWhenItsAnswerIsReplayed() throws Exception {
        PasskeyDevice device = newUserWithAPasskey("pk-challenge");
        Browser browser = browser();
        login(browser, "pk-challenge");
        var options = browser.post("/api/v1/auth/second-factor/options", null);
        String assertion = device.get(options.body());
        String body = "{\"webauthn\":%s}".formatted(assertion);

        assertThat(browser.post("/api/v1/auth/second-factor", body).statusCode())
                .isEqualTo(200);

        // Another sign-in with the same recorded answer: it has no challenge of its own to answer.
        Browser thief = browser();
        login(thief, "pk-challenge");
        var replay = thief.post("/api/v1/auth/second-factor", body);
        assertThat(replay.statusCode()).isEqualTo(401);
        assertThat(problem(replay)).endsWith("/second-factor-invalid");
        assertThat(thief.status("GET", ME)).isEqualTo(401);
        // And with a challenge of its own, an answer to a different challenge is refused.
        thief.post("/api/v1/auth/second-factor/options", null);
        var stale = thief.post("/api/v1/auth/second-factor", body);
        assertThat(stale.statusCode()).isEqualTo(401);
        assertThat(thief.status("GET", ME)).isEqualTo(401);
    }

    @Test
    void anotherUsersPasskeyDoesNotCompleteTheSignIn() throws Exception {
        newUserWithAPasskey("pk-victim");
        PasskeyDevice attacker = newUserWithAPasskey("pk-attacker");
        Browser browser = browser();
        login(browser, "pk-victim");
        // A valid assertion from the attacker's own passkey, for the victim's challenge.
        var options = browser.post("/api/v1/auth/second-factor/options", null);

        var response = browser.post(
                "/api/v1/auth/second-factor",
                "{\"webauthn\":%s}".formatted(attacker.getIgnoringAllowList(options.body())));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(problem(response)).endsWith("/second-factor-invalid");
        assertThat((String) JsonPath.read(response.body(), "$.detail")).contains("passkey");
        assertThat(browser.status("GET", ME)).isEqualTo(401);
        assertThat(users.findByUsername("pk-victim").orElseThrow().getFailedLoginCount())
                .isEqualTo(1);
    }

    @Test
    void thePasskeyOptionsNeedAPasswordThatWasRight() throws Exception {
        newUserWithAPasskey("pk-no-password");

        var response = browser().post("/api/v1/auth/second-factor/options", null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(problem(response)).endsWith("/sign-in-expired");
    }

    @Test
    void thePasskeyOptionsNeedTheCsrfToken() throws Exception {
        newUserWithAPasskey("pk-csrf");
        Browser browser = browser();
        login(browser, "pk-csrf");

        assertThat(browser.sendWithoutCsrf("POST", "/api/v1/auth/second-factor/options", null)
                        .statusCode())
                .isEqualTo(403);
    }

    @Test
    void anAccountWithoutAPasskeyIsOfferedNoChallenge() throws Exception {
        newUserWithAPasskey("pk-none-a");
        // An account whose factor is a code has no passkey to be challenged for.
        UUID id = newUser("pk-none-b");
        jdbc.sql("INSERT INTO local_totp (user_id, sealed) VALUES (?, '\\x00')")
                .param(id)
                .update();
        Browser browser = browser();
        login(browser, "pk-none-b");

        var response = browser.post("/api/v1/auth/second-factor/options", null);

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(problem(response)).endsWith("/no-passkey");
    }

    // ---- step-up -----------------------------------------------------------------------------

    @Test
    void aPasskeyCompletesAStepUpAfterThePassword() throws Exception {
        PasskeyDevice device = newUserWithAPasskey("pk-stepup");
        Browser browser = browser();
        login(browser, "pk-stepup");
        assertThat(answer(browser, device).statusCode()).isEqualTo(200);
        makeStale(browser);
        assertThat(problem(browser.post("/api/v1/auth/mfa/recovery-codes", null)))
                .endsWith("/reauthentication-required");
        var password = browser.post("/api/v1/auth/reauthenticate", "{\"password\":\"%s\"}".formatted(PASSWORD));
        assertThat((List<String>) JsonPath.read(password.body(), "$.methods")).startsWith("WEBAUTHN");

        var factor = answer(browser, device);

        assertThat(factor.statusCode()).as(factor.body()).isEqualTo(200);
        assertThat((String) JsonPath.read(factor.body(), "$.status")).isEqualTo("AUTHENTICATED");
        assertThat(browser.post("/api/v1/auth/mfa/recovery-codes", null).statusCode())
                .isEqualTo(200);
    }

    @Test
    void thePasskeyOptionsOfAStepUpNeedThePasswordFirst() throws Exception {
        PasskeyDevice device = newUserWithAPasskey("pk-stepup-order");
        Browser browser = browser();
        login(browser, "pk-stepup-order");
        answer(browser, device);
        makeStale(browser);

        var response = browser.post("/api/v1/auth/second-factor/options", null);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(problem(response)).endsWith("/reauthentication-failed");
    }

    // ---- removal -----------------------------------------------------------------------------

    private String passkeyId(Browser browser, String label) throws Exception {
        return JsonPath.<List<String>>read(
                        browser.send("GET", "/api/v1/auth/mfa", null).body(),
                        "$.passkeys[?(@.label == '" + label + "')].id")
                .getFirst();
    }

    @Test
    void aPasskeyIsRemovedWithAStepUpAndRemovingTheLastOneTakesTheRecoveryCodesWithIt() throws Exception {
        PasskeyDevice device = newUserWithAPasskey("pk-remove");
        Browser browser = browser();
        login(browser, "pk-remove");
        answer(browser, device);
        String id = passkeyId(browser, "Laptop");
        makeStale(browser);
        var refused = browser.send("DELETE", "/api/v1/auth/mfa/webauthn/" + id, null);
        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(problem(refused)).endsWith("/reauthentication-required");
        browser.post("/api/v1/auth/reauthenticate", "{\"password\":\"%s\"}".formatted(PASSWORD));
        assertThat(answer(browser, device).statusCode()).isEqualTo(200);

        var removed = browser.send("DELETE", "/api/v1/auth/mfa/webauthn/" + id, null);

        assertThat(removed.statusCode()).isEqualTo(204);
        var status = browser.send("GET", "/api/v1/auth/mfa", null).body();
        assertThat((List<Object>) JsonPath.read(status, "$.passkeys")).isEmpty();
        assertThat((Boolean) JsonPath.read(status, "$.enrolled")).isFalse();
        assertThat((Integer) JsonPath.read(status, "$.recoveryCodesRemaining")).isZero();
        assertThat(audited("MFA_REMOVE", "pk-remove")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM user_entities WHERE display_name = 'pk-remove'")
                        .query(Long.class)
                        .single())
                .as("the passkey is gone from the credential store")
                .isEqualTo(1);
        assertThat(jdbc.sql(
                                "SELECT count(*) FROM user_credentials c JOIN user_entities e ON c.user_entity_user_id = e.id"
                                        + " WHERE e.display_name = 'pk-remove'")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void aUserWhoseRoleRequiresAFactorCannotRemoveTheirLastPasskeyButCanOnceTheyHaveAnother() throws Exception {
        UUID id = newUser("pk-required-remove");
        requireMfa(id);
        PasskeyDevice first = new PasskeyDevice(ORIGIN);
        Browser enrolling = signedIn("pk-required-remove");
        assertThat(register(enrolling, first, "First").statusCode()).isEqualTo(200);
        Browser browser = browser();
        login(browser, "pk-required-remove");
        assertThat(answer(browser, first).statusCode()).isEqualTo(200);
        String firstId = passkeyId(browser, "First");

        var last = browser.send("DELETE", "/api/v1/auth/mfa/webauthn/" + firstId, null);

        assertThat(last.statusCode()).isEqualTo(409);
        assertThat(problem(last)).endsWith("/last-factor-required");
        assertThat((String) JsonPath.read(last.body(), "$.detail")).isEqualTo("Add another way to sign in first.");
        assertThat(register(browser, new PasskeyDevice(ORIGIN), "Second").statusCode())
                .isEqualTo(200);
        assertThat(browser.send("DELETE", "/api/v1/auth/mfa/webauthn/" + firstId, null)
                        .statusCode())
                .isEqualTo(204);
        assertThat((List<String>) JsonPath.read(
                        browser.send("GET", "/api/v1/auth/mfa", null).body(), "$.passkeys[*].label"))
                .containsExactly("Second");
    }

    @Test
    void anotherUsersPasskeyCannotBeRemovedAndAnUnknownIdIsNotFound() throws Exception {
        newUserWithAPasskey("pk-owner");
        PasskeyDevice mine = newUserWithAPasskey("pk-not-owner");
        Browser owner = browser();
        login(owner, "pk-owner");
        // Everything the owner has is in one place: sign the owner in with their own device to read their id.
        Browser other = browser();
        login(other, "pk-not-owner");
        answer(other, mine);
        String ownersId = jdbc.sql("SELECT credential_id FROM user_credentials c JOIN user_entities e"
                        + " ON c.user_entity_user_id = e.id WHERE e.display_name = 'pk-owner'")
                .query(String.class)
                .single();

        var response = other.send("DELETE", "/api/v1/auth/mfa/webauthn/" + ownersId, null);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(other.send("DELETE", "/api/v1/auth/mfa/webauthn/nonsense", null)
                        .statusCode())
                .isEqualTo(404);
        assertThat(jdbc.sql("SELECT count(*) FROM user_credentials WHERE credential_id = ?")
                        .param(ownersId)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }
}
