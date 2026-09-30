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
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;

/**
 * Signing in with a second factor, and the sessions restricted to enrolling one (ADR-0142), over
 * real HTTP against the JDBC session store: a session that holds only a half-finished sign-in has no
 * principal, so it can only be found by the cookie that carries it. TOTP codes are computed here the
 * way an authenticator app does; {@code last_step} is reset between uses because a code is accepted
 * once, and a test cannot wait 30 seconds for the next.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecondFactorLoginIntegrationTest extends PostgresIntegrationTest {

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

    private final AtomicInteger sources = new AtomicInteger();

    private record Enrolled(byte[] secret, List<String> recoveryCodes) {}

    // ---- fixtures ----------------------------------------------------------------------------

    private String nextSource() {
        int n = sources.incrementAndGet();
        return "10.20." + n / 250 + "." + n % 250;
    }

    private Browser browser() {
        return new Browser(port, AGENT, nextSource());
    }

    private UUID newUser(String username) {
        AppUserEntity user =
                AppUserEntity.local(username, username + "@example.test", passwordEncoder.encode(PASSWORD));
        user.setMustChangePassword(false);
        return users.save(user).getId();
    }

    /** Gives the user a custom role that requires a second factor and grants nothing else. */
    private void requireMfa(UUID userId) {
        RoleEntity role = new RoleEntity("mfa-" + UUID.randomUUID(), false);
        role.setRequiresMfa(true);
        role = roles.save(role);
        userRoles.save(new UserRoleEntity(userId, role.getId(), "GLOBAL", ScopeIds.GLOBAL));
    }

    private HttpResponse<String> login(Browser browser, String username, String password) throws Exception {
        return browser.post(
                "/api/v1/auth/login", "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password));
    }

    private Browser signedIn(String username) throws Exception {
        Browser browser = browser();
        assertThat(JsonPath.<String>read(login(browser, username, PASSWORD).body(), "$.status"))
                .isEqualTo("AUTHENTICATED");
        return browser;
    }

    private static String code(byte[] secret) {
        return Totp.code(secret, Totp.stepAt(Instant.now()), Totp.DIGITS);
    }

    /** A code the user has not used yet, as if 30 seconds had passed since the last one. */
    private String freshCode(String username, byte[] secret) {
        jdbc.sql("UPDATE local_totp SET last_step = 0 WHERE user_id = (SELECT id FROM app_user WHERE username = ?)")
                .param(username)
                .update();
        return code(secret);
    }

    /** Sets up an authenticator for the user of this signed-in browser. */
    private Enrolled enrol(Browser browser) throws Exception {
        var start = browser.post("/api/v1/auth/mfa/totp", null);
        assertThat(start.statusCode()).isEqualTo(200);
        byte[] secret = Base32.decode(JsonPath.read(start.body(), "$.secret"));
        var confirm = browser.post("/api/v1/auth/mfa/totp/confirm", "{\"code\":\"%s\"}".formatted(code(secret)));
        assertThat(confirm.statusCode()).isEqualTo(200);
        List<String> codes = JsonPath.read(confirm.body(), "$.recoveryCodes");
        return new Enrolled(secret, codes);
    }

    private Enrolled newEnrolledUser(String username) throws Exception {
        newUser(username);
        return enrol(signedIn(username));
    }

    private HttpResponse<String> secondFactor(Browser browser, String field, String value) throws Exception {
        return browser.post("/api/v1/auth/second-factor", "{\"%s\":\"%s\"}".formatted(field, value));
    }

    private static String problem(HttpResponse<String> response) {
        return JsonPath.read(response.body(), "$.type");
    }

    private long audited(String action, String target) {
        return jdbc.sql("SELECT count(*) FROM audit_event WHERE action = ? AND target_name = ?")
                .params(action, target)
                .query(Long.class)
                .single();
    }

    private int failedLogins(String username) {
        return users.findByUsername(username).orElseThrow().getFailedLoginCount();
    }

    private SessionFacts facts(String sessionId) {
        return store.findById(sessionId).getAttribute(SessionAuthentication.FACTS);
    }

    private static <S extends Session> void change(
            FindByIndexNameSessionRepository<S> repository, String sessionId, Consumer<S> edit) {
        S session = repository.findById(sessionId);
        edit.accept(session);
        repository.save(session);
    }

    private void makeStale(Browser browser) {
        change(store, browser.sessionId(), s -> {
            SessionFacts facts = s.getAttribute(SessionAuthentication.FACTS);
            s.setAttribute(
                    SessionAuthentication.FACTS,
                    facts.withAuthenticatedAt(Instant.now().minus(Duration.ofMinutes(10))));
        });
    }

    // ---- sign-in -----------------------------------------------------------------------------

    @Test
    void aCorrectPasswordAloneDoesNotSignInAnAccountWithAFactor() throws Exception {
        newEnrolledUser("sf-password-only");
        Browser thief = browser();

        var response = login(thief, "sf-password-only", PASSWORD);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat((String) JsonPath.read(response.body(), "$.status")).isEqualTo("SECOND_FACTOR_REQUIRED");
        assertThat((List<String>) JsonPath.read(response.body(), "$.methods")).containsExactly("TOTP", "RECOVERY_CODE");
        assertThat((Object) JsonPath.read(response.body(), "$.me")).isNull();
        assertThat(thief.status("GET", ME)).isEqualTo(401);
        assertThat(thief.status("GET", CONSOLE)).isEqualTo(401);
        assertThat(store.findByPrincipalName("sf-password-only")).hasSize(1); // the owner's, from enrolling
        assertThat(thief.sessionId()).isNotNull();
        assertThat((Object) store.findById(thief.sessionId()).getAttribute("SPRING_SECURITY_CONTEXT"))
                .isNull();
    }

    @Test
    void aTotpCodeCompletesTheSignInAndRecordsWhatVerifiedIt() throws Exception {
        Enrolled enrolled = newEnrolledUser("sf-totp-ok");
        jdbc.sql("UPDATE app_user SET failed_login_count = 3 WHERE username = 'sf-totp-ok'")
                .update();
        Browser browser = browser();
        login(browser, "sf-totp-ok", PASSWORD);
        assertThat(failedLogins("sf-totp-ok"))
                .as("the password alone clears nothing")
                .isEqualTo(3);

        var response = secondFactor(browser, "totpCode", freshCode("sf-totp-ok", enrolled.secret()));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat((String) JsonPath.read(response.body(), "$.status")).isEqualTo("AUTHENTICATED");
        assertThat((String) JsonPath.read(response.body(), "$.me.username")).isEqualTo("sf-totp-ok");
        assertThat(browser.status("GET", CONSOLE)).isEqualTo(200);
        assertThat(failedLogins("sf-totp-ok")).as("the whole sign-in finished").isZero();
        SessionFacts facts = facts(browser.sessionId());
        assertThat(facts.mfaMethod()).isEqualTo(SessionFacts.Method.TOTP);
        assertThat(facts.mfaVerifiedAt()).isNotNull();
        assertThat(facts.authenticatedAt()).isAfter(Instant.now().minusSeconds(30));
        assertThat(audited("SECOND_FACTOR", "sf-totp-ok")).isEqualTo(1);
    }

    @Test
    void theSecondFactorStepNeedsTheCsrfToken() throws Exception {
        Enrolled enrolled = newEnrolledUser("sf-csrf");
        Browser browser = browser();
        login(browser, "sf-csrf", PASSWORD);

        var response = browser.sendWithoutCsrf(
                "POST",
                "/api/v1/auth/second-factor",
                "{\"totpCode\":\"%s\"}".formatted(freshCode("sf-csrf", enrolled.secret())));

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(browser.status("GET", ME)).isEqualTo(401);
    }

    @Test
    void withoutASignInInProgressTheSecondFactorIsRefused() throws Exception {
        Enrolled enrolled = newEnrolledUser("sf-no-pending");

        var response = secondFactor(browser(), "totpCode", freshCode("sf-no-pending", enrolled.secret()));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(problem(response)).endsWith("/sign-in-expired");
    }

    @Test
    void aWrongCodeIsRefusedWithAReasonAndCountsAsAFailure() throws Exception {
        newEnrolledUser("sf-wrong");
        Browser browser = browser();
        login(browser, "sf-wrong", PASSWORD);

        var response = secondFactor(browser, "totpCode", "000000");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(problem(response)).endsWith("/second-factor-invalid");
        assertThat((String) JsonPath.read(response.body(), "$.detail")).contains("recovery code");
        assertThat(failedLogins("sf-wrong")).isEqualTo(1);
        assertThat(audited("SECOND_FACTOR_FAILED", "sf-wrong")).isEqualTo(1);
        assertThat(browser.status("GET", ME)).isEqualTo(401);
    }

    @Test
    void tenFailuresIncludingWrongCodesLockTheAccountAndItThenAnswersLikeAWrongPassword() throws Exception {
        Enrolled enrolled = newEnrolledUser("sf-lock");
        for (int i = 0; i < 4; i++) {
            assertThat(login(browser(), "sf-lock", "wrong").statusCode()).isEqualTo(401);
        }
        Browser browser = browser();
        login(browser, "sf-lock", PASSWORD);
        for (int i = 0; i < 6; i++) {
            assertThat(secondFactor(browser.from(nextSource()), "totpCode", "000000")
                            .statusCode())
                    .isEqualTo(401);
        }

        assertThat(users.findByUsername("sf-lock").orElseThrow().getLockedUntil())
                .isAfter(Instant.now());
        var locked = login(browser(), "sf-lock", PASSWORD);
        assertThat(locked.statusCode()).isEqualTo(401);
        assertThat(problem(locked)).endsWith("/invalid-credentials");
        assertThat(locked.body()).doesNotContain("SECOND_FACTOR_REQUIRED");
        // Even the right code, from the pending sign-in that was there before the lock, opens nothing.
        var late = secondFactor(browser, "totpCode", freshCode("sf-lock", enrolled.secret()));
        assertThat(late.statusCode()).isEqualTo(401);
        assertThat(browser.status("GET", ME)).isEqualTo(401);
    }

    @Test
    void repeatedWrongCodesFromOneSourceAreThrottled() throws Exception {
        newEnrolledUser("sf-throttle");
        Browser browser = browser();
        login(browser, "sf-throttle", PASSWORD);
        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            statuses.add(secondFactor(browser, "totpCode", "000000").statusCode());
        }

        assertThat(statuses).startsWith(401, 401, 401, 401, 401).endsWith(429);
    }

    @Test
    void aPasswordIsOnlyGoodForAFewMinutes() throws Exception {
        Enrolled enrolled = newEnrolledUser("sf-expiry");
        Browser browser = browser();
        login(browser, "sf-expiry", PASSWORD);
        change(
                store,
                browser.sessionId(),
                s -> s.setAttribute(
                        SessionAuthentication.PENDING_SECOND_FACTOR,
                        new SessionAuthentication.PendingSecondFactor(
                                users.findByUsername("sf-expiry").orElseThrow().getId(),
                                "local",
                                Instant.now().minus(Duration.ofMinutes(6)))));

        var response = secondFactor(browser, "totpCode", freshCode("sf-expiry", enrolled.secret()));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(problem(response)).endsWith("/sign-in-expired");
        assertThat(browser.status("GET", ME)).isEqualTo(401);
    }

    @Test
    void anAccountLockedAfterThePasswordIsRefusedAndTheSignInForgotten() throws Exception {
        Enrolled enrolled = newEnrolledUser("sf-locked-meanwhile");
        Browser browser = browser();
        login(browser, "sf-locked-meanwhile", PASSWORD);
        jdbc.sql("UPDATE app_user SET locked_until = now() + interval '10 minutes' WHERE username = ?")
                .param("sf-locked-meanwhile")
                .update();
        String code = freshCode("sf-locked-meanwhile", enrolled.secret());

        var refused = secondFactor(browser, "totpCode", code);

        assertThat(refused.statusCode()).isEqualTo(401);
        assertThat(problem(refused)).endsWith("/invalid-credentials");
        assertThat(problem(secondFactor(browser, "totpCode", code))).endsWith("/sign-in-expired");
    }

    @Test
    void aRecoveryCodeWorksOnceAndIsAudited() throws Exception {
        Enrolled enrolled = newEnrolledUser("sf-recovery");
        assertThat(enrolled.recoveryCodes()).hasSize(10).allMatch(c -> c.matches("[A-Z2-7]{5}-[A-Z2-7]{5}"));
        // As typed by a person: lower case, no dash.
        String typed = enrolled.recoveryCodes().getFirst().replace("-", "").toLowerCase();
        Browser first = browser();
        login(first, "sf-recovery", PASSWORD);

        var ok = secondFactor(first, "recoveryCode", typed);

        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(facts(first.sessionId()).mfaMethod()).isEqualTo(SessionFacts.Method.RECOVERY_CODE);
        assertThat(audited("RECOVERY_CODE_USE", "sf-recovery")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(
                        first.send("GET", "/api/v1/auth/mfa", null).body(), "$.recoveryCodesRemaining"))
                .isEqualTo(9);

        Browser second = browser();
        login(second, "sf-recovery", PASSWORD);
        var reused =
                secondFactor(second, "recoveryCode", enrolled.recoveryCodes().getFirst());
        assertThat(reused.statusCode()).isEqualTo(401);
        assertThat(problem(reused)).endsWith("/second-factor-invalid");
        assertThat(second.status("GET", ME)).isEqualTo(401);
    }

    // ---- replays -----------------------------------------------------------------------------

    @Test
    void aTotpCodeCannotBeReplayedEvenConcurrently() throws Exception {
        Enrolled enrolled = newEnrolledUser("sf-race-totp");
        Browser a = browser();
        Browser b = browser();
        login(a, "sf-race-totp", PASSWORD);
        login(b, "sf-race-totp", PASSWORD);
        String code = freshCode("sf-race-totp", enrolled.secret());

        List<Integer> statuses = race(
                () -> secondFactor(a, "totpCode", code).statusCode(),
                () -> secondFactor(b, "totpCode", code).statusCode());

        assertThat(statuses).containsExactlyInAnyOrder(200, 401);
        // And afterwards, from a third sign-in.
        Browser c = browser();
        login(c, "sf-race-totp", PASSWORD);
        assertThat(secondFactor(c, "totpCode", code).statusCode()).isEqualTo(401);
    }

    @Test
    void aRecoveryCodeCannotBeReplayedEvenConcurrently() throws Exception {
        Enrolled enrolled = newEnrolledUser("sf-race-recovery");
        Browser a = browser();
        Browser b = browser();
        login(a, "sf-race-recovery", PASSWORD);
        login(b, "sf-race-recovery", PASSWORD);
        String code = enrolled.recoveryCodes().getFirst();

        List<Integer> statuses = race(
                () -> secondFactor(a, "recoveryCode", code).statusCode(),
                () -> secondFactor(b, "recoveryCode", code).statusCode());

        assertThat(statuses).containsExactlyInAnyOrder(200, 401);
    }

    @SafeVarargs
    private static List<Integer> race(Callable<Integer>... attempts) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(attempts.length);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> running = new ArrayList<>();
            for (Callable<Integer> attempt : attempts) {
                running.add(pool.submit(() -> {
                    go.await();
                    return attempt.call();
                }));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : running) {
                statuses.add(f.get());
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- step-up -----------------------------------------------------------------------------

    @Test
    void aPasswordAloneDoesNotStepUpAnAccountWithAFactorUntilTheFactorIsGiven() throws Exception {
        Enrolled enrolled = newEnrolledUser("sf-stepup");
        Browser browser = browser();
        login(browser, "sf-stepup", PASSWORD);
        secondFactor(browser, "totpCode", freshCode("sf-stepup", enrolled.secret()));
        makeStale(browser);
        assertThat(problem(browser.post("/api/v1/auth/mfa/recovery-codes", null)))
                .endsWith("/reauthentication-required");

        var password = browser.post("/api/v1/auth/reauthenticate", "{\"password\":\"%s\"}".formatted(PASSWORD));

        assertThat(password.statusCode()).isEqualTo(200);
        assertThat((String) JsonPath.read(password.body(), "$.status")).isEqualTo("SECOND_FACTOR_REQUIRED");
        assertThat(problem(browser.post("/api/v1/auth/mfa/recovery-codes", null)))
                .endsWith("/reauthentication-required");

        var factor = secondFactor(browser, "totpCode", freshCode("sf-stepup", enrolled.secret()));

        assertThat(factor.statusCode()).isEqualTo(200);
        assertThat((String) JsonPath.read(factor.body(), "$.status")).isEqualTo("AUTHENTICATED");
        var codes = browser.post("/api/v1/auth/mfa/recovery-codes", null);
        assertThat(codes.statusCode()).isEqualTo(200);
        assertThat((List<String>) JsonPath.read(codes.body(), "$.codes"))
                .hasSize(10)
                .doesNotContainAnyElementsOf(enrolled.recoveryCodes());
        assertThat(audited("RECOVERY_CODES_REGENERATE", "sf-stepup")).isEqualTo(1);
        // The old codes no longer sign in.
        Browser later = browser();
        login(later, "sf-stepup", PASSWORD);
        assertThat(secondFactor(later, "recoveryCode", enrolled.recoveryCodes().getFirst())
                        .statusCode())
                .isEqualTo(401);
    }

    @Test
    void aSecondFactorForAStepUpNeedsThePasswordFirst() throws Exception {
        Enrolled enrolled = newEnrolledUser("sf-stepup-order");
        Browser browser = browser();
        login(browser, "sf-stepup-order", PASSWORD);
        secondFactor(browser, "totpCode", freshCode("sf-stepup-order", enrolled.secret()));
        makeStale(browser);

        var response = secondFactor(browser, "totpCode", freshCode("sf-stepup-order", enrolled.secret()));

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(problem(response)).endsWith("/reauthentication-failed");
        assertThat(problem(browser.post("/api/v1/auth/mfa/recovery-codes", null)))
                .endsWith("/reauthentication-required");
    }

    @Test
    void fiveWrongFactorsInAStepUpEndTheSession() throws Exception {
        Enrolled enrolled = newEnrolledUser("sf-stepup-fail");
        Browser browser = browser();
        login(browser, "sf-stepup-fail", PASSWORD);
        secondFactor(browser, "totpCode", freshCode("sf-stepup-fail", enrolled.secret()));
        browser.post("/api/v1/auth/reauthenticate", "{\"password\":\"%s\"}".formatted(PASSWORD));

        String sessionId = browser.sessionId();
        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            statuses.add(secondFactor(browser, "totpCode", "000000").statusCode());
        }

        assertThat(statuses).containsOnly(401);
        assertThat(browser.status("GET", ME)).isEqualTo(401);
        assertThat(store.findById(sessionId)).isNull();
    }

    // ---- enrolment ---------------------------------------------------------------------------

    @Test
    void anAccountWhoseRoleRequiresMfaMayOnlyEnrolUntilItHas() throws Exception {
        UUID id = newUser("sf-required");
        requireMfa(id);
        Browser browser = browser();

        var signIn = login(browser, "sf-required", PASSWORD);

        assertThat(signIn.statusCode()).isEqualTo(200);
        assertThat((String) JsonPath.read(signIn.body(), "$.status")).isEqualTo("AUTHENTICATED");
        assertThat((Boolean) JsonPath.read(signIn.body(), "$.me.secondFactorEnrolmentRequired"))
                .isTrue();
        for (String path : List.of(CONSOLE, "/api/v1/clusters", "/api/v1/users")) {
            var refused = browser.send("GET", path, null);
            assertThat(refused.statusCode()).as(path).isEqualTo(423);
            assertThat(problem(refused)).endsWith("/mfa-enrolment-required");
        }
        assertThat(problem(browser.post("/api/v1/auth/mfa/recovery-codes", null)))
                .endsWith("/mfa-enrolment-required");
        assertThat(problem(browser.post("/api/v1/auth/reauthenticate", "{\"password\":\"x\"}")))
                .endsWith("/mfa-enrolment-required");
        assertThat(browser.status("GET", ME)).isEqualTo(200);
        var status = browser.send("GET", "/api/v1/auth/mfa", null);
        assertThat(status.statusCode()).isEqualTo(200);
        assertThat((Boolean) JsonPath.read(status.body(), "$.required")).isTrue();
        assertThat((Boolean) JsonPath.read(status.body(), "$.enrolled")).isFalse();

        var start = browser.post("/api/v1/auth/mfa/totp", null);
        assertThat(start.statusCode()).isEqualTo(200);
        String uri = JsonPath.read(start.body(), "$.otpauthUri");
        String secret = JsonPath.read(start.body(), "$.secret");
        assertThat(uri)
                .isEqualTo("otpauth://totp/Artemis%20Studio:sf-required?secret=" + secret
                        + "&issuer=Artemis%20Studio&algorithm=SHA1&digits=6&period=30");
        var wrong = browser.post("/api/v1/auth/mfa/totp/confirm", "{\"code\":\"000000\"}");
        assertThat(wrong.statusCode()).isEqualTo(400);
        assertThat(browser.status("GET", CONSOLE)).as("still restricted").isEqualTo(423);

        var confirm = browser.post(
                "/api/v1/auth/mfa/totp/confirm", "{\"code\":\"%s\"}".formatted(code(Base32.decode(secret))));

        assertThat(confirm.statusCode()).isEqualTo(200);
        assertThat((List<String>) JsonPath.read(confirm.body(), "$.recoveryCodes"))
                .hasSize(10);
        assertThat(browser.status("GET", CONSOLE)).isEqualTo(200);
        assertThat((Boolean) JsonPath.read(browser.send("GET", ME, null).body(), "$.secondFactorEnrolmentRequired"))
                .isFalse();
        SessionFacts facts = facts(browser.sessionId());
        assertThat(facts.mfaMethod()).isEqualTo(SessionFacts.Method.TOTP);
        assertThat(facts.mfaVerifiedAt()).isNotNull();
        // Trust on first use, audited with the address it came from.
        assertThat(jdbc.sql("SELECT host(source_ip) FROM audit_event WHERE action = 'MFA_ENROL' AND target_name = ?")
                        .param("sf-required")
                        .query(String.class)
                        .single())
                .startsWith("10.20.");
    }

    @Test
    void passkeysAreUnavailableUntilThePublicAddressIsSetAndTheReportSaysWhatToSet() throws Exception {
        newUser("sf-no-address");
        Browser browser = signedIn("sf-no-address");

        var status = browser.send("GET", "/api/v1/auth/mfa", null).body();
        assertThat((Boolean) JsonPath.read(status, "$.webauthn.available")).isFalse();
        assertThat((String) JsonPath.read(status, "$.webauthn.reason"))
                .isEqualTo("Set ARTEMIS_STUDIO_PUBLIC_URL to the address people open Studio at to enable passkeys.");
        assertThat((List<Object>) JsonPath.read(status, "$.passkeys")).isEmpty();
        var options = browser.post("/api/v1/auth/mfa/webauthn/options", null);
        assertThat(options.statusCode()).isEqualTo(409);
        assertThat(problem(options)).endsWith("/passkeys-unavailable");
        // An authenticator app still works.
        assertThat(enrol(browser).recoveryCodes()).hasSize(10);
    }

    @Test
    void enrollingDoesNotMakeAStaleSessionFresh() throws Exception {
        newUser("sf-stale-enrol");
        Browser browser = signedIn("sf-stale-enrol");
        makeStale(browser);
        Instant before = facts(browser.sessionId()).authenticatedAt();

        enrol(browser);

        SessionFacts facts = facts(browser.sessionId());
        assertThat(facts.authenticatedAt()).isEqualTo(before);
        assertThat(facts.mfaVerifiedAt()).isNotNull();
    }

    @Test
    void addingAFactorWhenOneExistsNeedsAStepUpAndTheActiveSecretSurvivesUntilConfirmed() throws Exception {
        Enrolled first = newEnrolledUser("sf-replace");
        Browser browser = browser();
        login(browser, "sf-replace", PASSWORD);
        secondFactor(browser, "totpCode", freshCode("sf-replace", first.secret()));
        makeStale(browser);
        assertThat(problem(browser.post("/api/v1/auth/mfa/totp", null))).endsWith("/reauthentication-required");
        browser.post("/api/v1/auth/reauthenticate", "{\"password\":\"%s\"}".formatted(PASSWORD));
        assertThat(secondFactor(browser, "totpCode", freshCode("sf-replace", first.secret()))
                        .statusCode())
                .isEqualTo(200);

        var start = browser.post("/api/v1/auth/mfa/totp", null);
        byte[] replacement = Base32.decode(JsonPath.read(start.body(), "$.secret"));

        // Nothing has changed until a code from the new secret is confirmed.
        Browser other = browser();
        login(other, "sf-replace", PASSWORD);
        assertThat(secondFactor(other, "totpCode", freshCode("sf-replace", first.secret()))
                        .statusCode())
                .isEqualTo(200);
        var confirm = browser.post(
                "/api/v1/auth/mfa/totp/confirm", "{\"code\":\"%s\"}".formatted(freshCode("sf-replace", replacement)));
        assertThat(confirm.statusCode()).isEqualTo(200);
        assertThat((Object) JsonPath.read(confirm.body(), "$.recoveryCodes"))
                .as("not the first factor")
                .isNull();

        Browser afterwards = browser();
        login(afterwards, "sf-replace", PASSWORD);
        assertThat(secondFactor(afterwards, "totpCode", freshCode("sf-replace", first.secret()))
                        .statusCode())
                .isEqualTo(401);
        assertThat(secondFactor(afterwards, "totpCode", freshCode("sf-replace", replacement))
                        .statusCode())
                .isEqualTo(200);
    }

    @Test
    void theBootstrapAdministratorChangesThePasswordThenEnrolsThenReachesTheConsole() throws Exception {
        AppUserEntity admin = AppUserEntity.local("sf-bootstrap", null, passwordEncoder.encode(PASSWORD));
        admin.setMustChangePassword(true);
        UUID id = users.save(admin).getId();
        userRoles.save(
                new UserRoleEntity(id, roles.findByName("ADMIN").orElseThrow().getId(), "GLOBAL", ScopeIds.GLOBAL));
        Browser browser = browser();

        var signIn = login(browser, "sf-bootstrap", PASSWORD);

        assertThat((Boolean) JsonPath.read(signIn.body(), "$.me.mustChangePassword"))
                .isTrue();
        assertThat((Boolean) JsonPath.read(signIn.body(), "$.me.secondFactorEnrolmentRequired"))
                .isTrue();
        assertThat(problem(browser.send("GET", "/api/v1/auth/mfa", null))).endsWith("/must-change-password");
        assertThat(problem(browser.send("GET", "/api/v1/users", null))).endsWith("/must-change-password");

        var changed = browser.post(
                "/api/v1/auth/password",
                "{\"currentPassword\":\"%s\",\"newPassword\":\"Tr0ub4dor&3-horse-staple\"}".formatted(PASSWORD));

        assertThat(changed.statusCode()).isEqualTo(204);
        var me = browser.send("GET", ME, null).body();
        assertThat((Boolean) JsonPath.read(me, "$.mustChangePassword")).isFalse();
        assertThat((Boolean) JsonPath.read(me, "$.secondFactorEnrolmentRequired"))
                .isTrue();
        assertThat(problem(browser.send("GET", "/api/v1/users", null))).endsWith("/mfa-enrolment-required");

        enrol(browser);

        assertThat(browser.status("GET", "/api/v1/users")).isEqualTo(200);
    }
}
