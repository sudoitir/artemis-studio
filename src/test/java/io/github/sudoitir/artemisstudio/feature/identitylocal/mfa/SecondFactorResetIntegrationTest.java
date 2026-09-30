package io.github.sudoitir.artemisstudio.feature.identitylocal.mfa;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.github.sudoitir.artemisstudio.support.AccountIntegrationTest;
import io.github.sudoitir.artemisstudio.support.Browser;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * An administrator resetting another user's second factors (ADR-0143): everything the user could sign
 * in or act with ends, it is audited, and the guards around it: step-up, not for oneself, and an
 * administrator who has verified a factor themselves when the target is required to hold one.
 */
class SecondFactorResetIntegrationTest extends AccountIntegrationTest {

    private UUID idOf(String username) {
        return users.findByUsername(username).orElseThrow().getId();
    }

    private long count(String table, String username) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE user_id = ?")
                .param(idOf(username))
                .query(Long.class)
                .single();
    }

    private String resetPath(String username) {
        return "/api/v1/users/" + idOf(username) + "/second-factors";
    }

    /** A user who holds everything a reset removes: an authenticator app, recovery codes, a trusted device, a token, a session. */
    private Browser userWithEverything(String username) throws Exception {
        newAdministrator(username);
        Enrolled enrolled = enrol(signedIn(username));
        Browser browser = browser();
        login(browser, username);
        browser.post(
                "/api/v1/auth/second-factor",
                "{\"totpCode\":\"%s\",\"trustDevice\":true}".formatted(freshCode(username, enrolled)));
        var token = browser.post("/api/v1/tokens", mintBody("ci"));
        assertThat(token.statusCode()).isEqualTo(201);
        return browser;
    }

    @Test
    void aResetRemovesEverythingTheUserCouldSignInOrActWithAndIsAudited() throws Exception {
        Browser target = userWithEverything("rs-target");
        newAdministrator("rs-admin");
        Browser admin = signedIn("rs-admin");
        assertThat(target.status("GET", CONSOLE)).isEqualTo(200);
        assertThat(count("local_totp", "rs-target")).isEqualTo(1);
        assertThat(count("local_recovery_code", "rs-target")).isEqualTo(10);
        assertThat(count("local_trusted_device", "rs-target")).isEqualTo(1);

        var response = admin.send("DELETE", resetPath("rs-target"), null);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat((String) JsonPath.read(response.body(), "$.username")).isEqualTo("rs-target");
        assertThat((List<String>) JsonPath.read(response.body(), "$.secondFactors"))
                .isEmpty();
        assertThat(count("local_totp", "rs-target")).isZero();
        assertThat(count("local_recovery_code", "rs-target")).isZero();
        assertThat(count("local_trusted_device", "rs-target")).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM api_token WHERE user_id = ? AND revoked_at IS NULL")
                        .param(idOf("rs-target"))
                        .query(Long.class)
                        .single())
                .as("the token is revoked")
                .isZero();
        assertThat(target.status("GET", CONSOLE)).as("the session ended").isEqualTo(401);
        assertThat(audited("MFA_RESET", "rs-target")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT username FROM audit_event WHERE action = 'MFA_RESET' AND target_name = ?")
                        .param("rs-target")
                        .query(String.class)
                        .single())
                .isEqualTo("rs-admin");
        // The user signs in with the password alone again.
        assertThat((String) JsonPath.read(login(browser(), "rs-target").body(), "$.status"))
                .isEqualTo("AUTHENTICATED");
    }

    @Test
    void theUserListSaysWhichFactorsEachUserHolds() throws Exception {
        newEnrolledUser("rs-list-enrolled");
        newUser("rs-list-bare");
        newAdministrator("rs-list-admin");
        Browser admin = signedIn("rs-list-admin");

        var users = admin.send("GET", "/api/v1/users", null).body();

        assertThat((List<String>) JsonPath.read(users, "$.data[?(@.username == 'rs-list-enrolled')].secondFactors[*]"))
                .containsExactly("TOTP");
        assertThat((List<Object>) JsonPath.read(users, "$.data[?(@.username == 'rs-list-bare')].secondFactors[*]"))
                .isEmpty();
        assertThat((List<Boolean>) JsonPath.read(users, "$.data[?(@.username == 'rs-list-bare')].secondFactorRequired"))
                .containsExactly(false);
    }

    @Test
    void resettingOneselfIsRefusedAndPointsToRecoveryCodes() throws Exception {
        newAdministrator("rs-self");
        Browser admin = signedIn("rs-self");

        var response = admin.send("DELETE", resetPath("rs-self"), null);

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(problem(response)).endsWith("/self-reset");
        assertThat((String) JsonPath.read(response.body(), "$.detail"))
                .isEqualTo("Use one of your recovery codes, or ask another administrator.");
    }

    @Test
    void aResetNeedsAStepUp() throws Exception {
        newEnrolledUser("rs-stale-target");
        newAdministrator("rs-stale-admin");
        Browser admin = signedIn("rs-stale-admin");
        makeStale(admin);

        var response = admin.send("DELETE", resetPath("rs-stale-target"), null);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(problem(response)).endsWith("/reauthentication-required");
        assertThat(count("local_totp", "rs-stale-target")).isEqualTo(1);
    }

    @Test
    void aResetNeedsThePermission() throws Exception {
        newEnrolledUser("rs-perm-target");
        newUser("rs-perm-plain");
        Browser plain = signedIn("rs-perm-plain");

        var response = plain.send("DELETE", resetPath("rs-perm-target"), null);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(count("local_totp", "rs-perm-target")).isEqualTo(1);
    }

    @Test
    void aTargetWhoIsRequiredToHoldAFactorCanOnlyBeResetByAnAdministratorWhoVerifiedOne() throws Exception {
        UUID target = newUser("rs-required-target");
        requireMfa(target);
        enrolAsRequired("rs-required-target");
        newAdministrator("rs-required-admin");
        Browser withoutFactor = signedIn("rs-required-admin");

        var refused = withoutFactor.send("DELETE", resetPath("rs-required-target"), null);

        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(problem(refused)).endsWith("/mfa-required");
        assertThat((String) JsonPath.read(refused.body(), "$.detail")).contains("second factor");
        assertThat(count("local_totp", "rs-required-target")).isEqualTo(1);

        Enrolled adminFactor = enrol(withoutFactor);
        Browser withFactor = signedInWithSecondFactor("rs-required-admin", adminFactor);

        var reset = withFactor.send("DELETE", resetPath("rs-required-target"), null);

        assertThat(reset.statusCode()).as(reset.body()).isEqualTo(200);
        assertThat(count("local_totp", "rs-required-target")).isZero();
        // The target is taken to enrolment at the next sign-in.
        Browser next = browser();
        var signIn = login(next, "rs-required-target");
        assertThat((Boolean) JsonPath.read(signIn.body(), "$.me.secondFactorEnrolmentRequired"))
                .isTrue();
        assertThat(next.status("GET", CONSOLE)).isEqualTo(423);
    }

    /** Enrols an authenticator app for a user whose role requires one, through its restricted session. */
    private void enrolAsRequired(String username) throws Exception {
        enrol(signedIn(username));
    }

    @Test
    void aTargetWhoIsNotRequiredCanBeResetByAnAdministratorWithoutAFactor() throws Exception {
        newEnrolledUser("rs-optional-target");
        newAdministrator("rs-optional-admin");
        Browser admin = signedIn("rs-optional-admin");

        assertThat(admin.send("DELETE", resetPath("rs-optional-target"), null).statusCode())
                .isEqualTo(200);
    }

    @Test
    void aTokenCannotResetFactors() throws Exception {
        newEnrolledUser("rs-token-target");
        newAdministrator("rs-token-admin");
        Browser admin = signedIn("rs-token-admin");
        String token = JsonPath.read(
                admin.post("/api/v1/tokens", mintBody("ci").replace("cluster:read", "user:admin"))
                        .body(),
                "$.value");

        var response = browser().send("DELETE", resetPath("rs-token-target"), null, "Authorization", "Bearer " + token);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(count("local_totp", "rs-token-target")).isEqualTo(1);
    }
}
