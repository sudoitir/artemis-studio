package io.github.sudoitir.artemisstudio.feature.identitylocal.mfa;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.github.sudoitir.artemisstudio.support.AccountIntegrationTest;
import io.github.sudoitir.artemisstudio.support.Browser;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Removing a second factor (ADR-0142): it needs a step-up, a user whose role requires a factor keeps
 * one, and removing the last one takes the recovery codes and trusted devices with it.
 */
class MfaManagementIntegrationTest extends AccountIntegrationTest {

    private long count(String table, String username) {
        return jdbc.sql("SELECT count(*) FROM " + table
                        + " WHERE user_id = (SELECT id FROM app_user WHERE username = ?)")
                .param(username)
                .query(Long.class)
                .single();
    }

    /** Signs in with the password and a code, trusting the browser. */
    private Browser signedInTrusting(String username, Enrolled enrolled) throws Exception {
        Browser browser = browser();
        login(browser, username);
        browser.post(
                "/api/v1/auth/second-factor",
                "{\"totpCode\":\"%s\",\"trustDevice\":true}".formatted(freshCode(username, enrolled)));
        return browser;
    }

    @Test
    void removingTheOnlyAuthenticatorAppOfAUserWhoDoesNotRequireOneMakesThemPasswordOnlyAgain() throws Exception {
        Enrolled enrolled = newEnrolledUser("mm-optional");
        Browser browser = signedInTrusting("mm-optional", enrolled);
        assertThat(count("local_recovery_code", "mm-optional")).isEqualTo(10);
        assertThat(count("local_trusted_device", "mm-optional")).isEqualTo(1);

        var response = browser.send("DELETE", "/api/v1/auth/mfa/totp", null);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(204);
        assertThat(count("local_totp", "mm-optional")).isZero();
        assertThat(count("local_recovery_code", "mm-optional"))
                .as("codes go with the last factor")
                .isZero();
        assertThat(count("local_trusted_device", "mm-optional")).isZero();
        assertThat(audited("MFA_REMOVE", "mm-optional")).isEqualTo(1);
        var status = browser.send("GET", "/api/v1/auth/mfa", null).body();
        assertThat((Boolean) JsonPath.read(status, "$.enrolled")).isFalse();
        assertThat((Integer) JsonPath.read(status, "$.recoveryCodesRemaining")).isZero();
        assertThat((String) JsonPath.read(login(browser(), "mm-optional").body(), "$.status"))
                .isEqualTo("AUTHENTICATED");
    }

    @Test
    void removingAFactorNeedsAStepUp() throws Exception {
        Enrolled enrolled = newEnrolledUser("mm-stepup");
        Browser browser = signedInWithSecondFactor("mm-stepup", enrolled);
        makeStale(browser);

        var response = browser.send("DELETE", "/api/v1/auth/mfa/totp", null);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(problem(response)).endsWith("/reauthentication-required");
        assertThat(count("local_totp", "mm-stepup")).isEqualTo(1);
    }

    @Test
    void aUserWhoseRoleRequiresAFactorCannotRemoveTheirLastOne() throws Exception {
        UUID id = newUser("mm-required");
        requireMfa(id);
        Enrolled enrolled = enrol(signedIn("mm-required"));
        Browser browser = signedInWithSecondFactor("mm-required", enrolled);

        var response = browser.send("DELETE", "/api/v1/auth/mfa/totp", null);

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(problem(response)).endsWith("/last-factor-required");
        assertThat((String) JsonPath.read(response.body(), "$.detail")).isEqualTo("Add another way to sign in first.");
        assertThat(count("local_totp", "mm-required")).isEqualTo(1);
        assertThat(count("local_recovery_code", "mm-required")).isEqualTo(10);
    }

    @Test
    void removingAnAuthenticatorAppThatIsNotThere() throws Exception {
        newUser("mm-none");
        Browser browser = signedIn("mm-none");

        var response = browser.send("DELETE", "/api/v1/auth/mfa/totp", null);

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(problem(response)).endsWith("/no-totp");
    }

    @Test
    void aTokenCannotRemoveAFactor() throws Exception {
        newAdministrator("mm-token");
        Browser browser = signedIn("mm-token");
        enrol(browser);
        String token =
                JsonPath.read(browser.post("/api/v1/tokens", mintBody("ci")).body(), "$.value");

        var response = browser().send("DELETE", "/api/v1/auth/mfa/totp", null, "Authorization", "Bearer " + token);

        assertThat(response.statusCode()).isIn(401, 403);
        assertThat(count("local_totp", "mm-token")).isEqualTo(1);
    }
}
