package io.github.sudoitir.artemisstudio.feature.identitylocal.mfa;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.github.sudoitir.artemisstudio.kernel.security.SessionFacts;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsService;
import io.github.sudoitir.artemisstudio.support.AccountIntegrationTest;
import io.github.sudoitir.artemisstudio.support.AdminAuthenticationExtension;
import io.github.sudoitir.artemisstudio.support.Browser;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Trusted devices (ADR-0142): the cookie, what is stored, what it skips and what it never does, the
 * lifetime setting, the lock, and every way a device is revoked. Over real HTTP, because the point is
 * what a browser does with the cookie.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class TrustedDeviceIntegrationTest extends AccountIntegrationTest {

    private static final String LIFETIME = "identity-local.mfa.trusted-device-lifetime";
    private static final String COOKIE = "as_trusted_device";

    @Autowired
    SettingsService settings;

    @AfterEach
    void restoreTheLifetime() {
        settings.reset(LIFETIME);
    }

    // ---- helpers -----------------------------------------------------------------------------

    /** Signs in with the password and a code, asking to trust the browser. */
    private HttpResponse<String> signInTrusting(Browser browser, String username, Enrolled enrolled, boolean trust)
            throws Exception {
        login(browser, username);
        return browser.post(
                "/api/v1/auth/second-factor",
                "{\"totpCode\":\"%s\",\"trustDevice\":%s}".formatted(freshCode(username, enrolled), trust));
    }

    private static List<String> cookies(HttpResponse<String> response) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(c -> c.startsWith(COOKIE + "="))
                .toList();
    }

    private long devices(String username) {
        return jdbc.sql("SELECT count(*) FROM local_trusted_device WHERE user_id = "
                        + "(SELECT id FROM app_user WHERE username = ?)")
                .param(username)
                .query(Long.class)
                .single();
    }

    private void lifetime(String value) {
        settings.put(LIFETIME, value);
    }

    // ---- the cookie --------------------------------------------------------------------------

    @Test
    void trustingADeviceSetsAScopedCookieAndKeepsOnlyTheHashOfItsToken() throws Exception {
        Enrolled enrolled = newEnrolledUser("td-cookie");
        Browser browser = browser();

        var response = signInTrusting(browser, "td-cookie", enrolled, true);

        assertThat((String) JsonPath.read(response.body(), "$.status")).isEqualTo("AUTHENTICATED");
        List<String> set = cookies(response);
        assertThat(set).hasSize(1);
        String cookie = set.getFirst();
        assertThat(cookie)
                .contains("HttpOnly")
                .contains("SameSite=Strict")
                .contains("Path=/api/v1/auth")
                .contains("Max-Age=2592000"); // 30 days
        assertThat(cookie).doesNotContain("Secure"); // plain HTTP, like local development
        String token = cookie.substring((COOKIE + "=").length(), cookie.indexOf(';'));
        assertThat(token).matches("[A-Za-z0-9_-]{43}"); // 32 random bytes, base64url without padding
        byte[] stored = jdbc.sql("SELECT token_hash FROM local_trusted_device WHERE user_id ="
                        + " (SELECT id FROM app_user WHERE username = 'td-cookie')")
                .query(byte[].class)
                .single();
        assertThat(HexFormat.of().formatHex(stored))
                .isEqualTo(HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes())));
        assertThat(jdbc.sql("SELECT user_agent FROM local_trusted_device")
                        .query(String.class)
                        .list())
                .contains(AGENT);
        assertThat(audited("TRUSTED_DEVICE_ADD", "td-cookie")).isEqualTo(1);
    }

    @Test
    void noBoxNoCookieAndNoRow() throws Exception {
        Enrolled enrolled = newEnrolledUser("td-unticked");

        var response = signInTrusting(browser(), "td-unticked", enrolled, false);

        assertThat(cookies(response)).isEmpty();
        assertThat(devices("td-unticked")).isZero();
    }

    @Test
    void theSecondFactorStepSaysForHowManyDaysADeviceIsTrusted() throws Exception {
        newEnrolledUser("td-days");

        var thirty = login(browser(), "td-days");
        assertThat((Integer) JsonPath.read(thirty.body(), "$.trustDeviceDays")).isEqualTo(30);

        lifetime("36h");
        assertThat((Integer) JsonPath.read(login(browser(), "td-days").body(), "$.trustDeviceDays"))
                .as("a part of a day counts as the day")
                .isEqualTo(2);

        lifetime("0");
        assertThat((Integer) JsonPath.read(login(browser(), "td-days").body(), "$.trustDeviceDays"))
                .isZero();
    }

    // ---- what it skips, and what it never does ----------------------------------------------

    @Test
    void aTrustedDeviceSignsInWithThePasswordAloneButNeverSatisfiesAStepUp() throws Exception {
        Enrolled enrolled = newEnrolledUser("td-skip");
        Browser browser = browser();
        signInTrusting(browser, "td-skip", enrolled, true);
        Instant before = jdbc.sql("SELECT last_used_at FROM local_trusted_device WHERE user_id ="
                        + " (SELECT id FROM app_user WHERE username = 'td-skip')")
                .query(java.sql.Timestamp.class)
                .single()
                .toInstant();
        browser.post("/api/v1/auth/logout", null);

        var response = login(browser, "td-skip");

        assertThat((String) JsonPath.read(response.body(), "$.status")).isEqualTo("AUTHENTICATED");
        assertThat(browser.status("GET", CONSOLE)).isEqualTo(200);
        SessionFacts facts = facts(browser.sessionId());
        assertThat(facts.mfaMethod()).isEqualTo(SessionFacts.Method.TRUSTED_DEVICE);
        assertThat(facts.mfaVerifiedAt()).isNotNull();
        assertThat(facts.authenticatedAt()).as("a trusted device is not fresh").isNull();
        // Anything that asks the user to confirm it is them still asks for the password and the factor.
        var stepUp = browser.post("/api/v1/auth/mfa/recovery-codes", null);
        assertThat(stepUp.statusCode()).isEqualTo(403);
        assertThat(problem(stepUp)).endsWith("/reauthentication-required");
        assertThat((String) JsonPath.read(
                        browser.post("/api/v1/auth/reauthenticate", "{\"password\":\"%s\"}".formatted(PASSWORD))
                                .body(),
                        "$.status"))
                .isEqualTo("SECOND_FACTOR_REQUIRED");
        assertThat(jdbc.sql("SELECT last_used_at FROM local_trusted_device WHERE user_id ="
                                + " (SELECT id FROM app_user WHERE username = 'td-skip')")
                        .query(java.sql.Timestamp.class)
                        .single()
                        .toInstant())
                .isAfter(before);
        assertThat(audited("SECOND_FACTOR", "td-skip")).isEqualTo(2); // the code, then the trusted device
    }

    @Test
    void aWrongPasswordOnATrustedDeviceIsStillAWrongPassword() throws Exception {
        Enrolled enrolled = newEnrolledUser("td-wrong");
        Browser browser = browser();
        signInTrusting(browser, "td-wrong", enrolled, true);
        browser.post("/api/v1/auth/logout", null);

        var response = browser.post("/api/v1/auth/login", "{\"username\":\"td-wrong\",\"password\":\"nope\"}");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(users.findByUsername("td-wrong").orElseThrow().getFailedLoginCount())
                .isEqualTo(1);
    }

    @Test
    void anotherUsersDeviceIsNotThisUsers() throws Exception {
        Enrolled alice = newEnrolledUser("td-alice");
        newEnrolledUser("td-bob");
        Browser shared = browser();
        signInTrusting(shared, "td-alice", alice, true);
        shared.post("/api/v1/auth/logout", null);

        var response = login(shared, "td-bob");

        assertThat((String) JsonPath.read(response.body(), "$.status")).isEqualTo("SECOND_FACTOR_REQUIRED");
    }

    @Test
    void aTrustedDeviceLetsItsOwnerInWhileTheAccountIsLockedElsewhere() throws Exception {
        Enrolled enrolled = newEnrolledUser("td-lock");
        Browser owner = browser();
        signInTrusting(owner, "td-lock", enrolled, true);
        owner.post("/api/v1/auth/logout", null);
        jdbc.sql("UPDATE app_user SET locked_until = now() + interval '10 minutes', failed_login_count = 10"
                        + " WHERE username = 'td-lock'")
                .update();

        var stranger = login(browser(), "td-lock");
        assertThat(stranger.statusCode()).isEqualTo(401);
        assertThat(problem(stranger)).endsWith("/invalid-credentials");

        var response = login(owner, "td-lock");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat((String) JsonPath.read(response.body(), "$.status")).isEqualTo("AUTHENTICATED");
        assertThat(owner.status("GET", CONSOLE)).isEqualTo(200);
    }

    // ---- the lifetime ------------------------------------------------------------------------

    @Test
    void zeroTurnsTrustedDevicesOffIgnoresTheirCookiesAndClearsThem() throws Exception {
        Enrolled enrolled = newEnrolledUser("td-off");
        Browser browser = browser();
        signInTrusting(browser, "td-off", enrolled, true);
        browser.post("/api/v1/auth/logout", null);
        lifetime("0");

        var response = login(browser, "td-off");

        assertThat((String) JsonPath.read(response.body(), "$.status")).isEqualTo("SECOND_FACTOR_REQUIRED");
        assertThat(cookies(response)).hasSize(1).allMatch(c -> c.contains("Max-Age=0"));
        // Asking to trust the browser now does nothing.
        var again = browser.post(
                "/api/v1/auth/second-factor",
                "{\"totpCode\":\"%s\",\"trustDevice\":true}".formatted(freshCode("td-off", enrolled)));
        assertThat((String) JsonPath.read(again.body(), "$.status")).isEqualTo("AUTHENTICATED");
        assertThat(cookies(again)).isEmpty();
        assertThat(devices("td-off")).isEqualTo(1); // only the first, which is ignored
    }

    @Test
    void aDeviceCountsUntilTheEarlierOfItsOwnExpiryAndTheLifetimeNow() throws Exception {
        Enrolled enrolled = newEnrolledUser("td-expiry");
        Browser browser = browser();
        signInTrusting(browser, "td-expiry", enrolled, true);
        browser.post("/api/v1/auth/logout", null);

        // Two days old, and the lifetime is cut to one: no longer trusted, though its own expiry is far off.
        jdbc.sql("UPDATE local_trusted_device SET created_at = now() - interval '2 days'")
                .update();
        lifetime("1d");
        assertThat((String) JsonPath.read(login(browser, "td-expiry").body(), "$.status"))
                .isEqualTo("SECOND_FACTOR_REQUIRED");
        browser.post("/api/v1/auth/logout", null);

        // Restored to 30 days it counts again, because the row still says it does.
        lifetime("30d");
        assertThat((String) JsonPath.read(login(browser, "td-expiry").body(), "$.status"))
                .isEqualTo("AUTHENTICATED");
        browser.post("/api/v1/auth/logout", null);

        // Its own expiry has passed: not trusted, however long the lifetime.
        jdbc.sql("UPDATE local_trusted_device SET expires_at = now() - interval '1 minute'")
                .update();
        assertThat((String) JsonPath.read(login(browser, "td-expiry").body(), "$.status"))
                .isEqualTo("SECOND_FACTOR_REQUIRED");
    }

    // ---- listing and revoking ----------------------------------------------------------------

    @Test
    void theUserSeesTheirDevicesAndWhichIsThisOne() throws Exception {
        Enrolled enrolled = newEnrolledUser("td-list");
        Browser laptop = browser();
        signInTrusting(laptop, "td-list", enrolled, true);
        Browser phone = browser();
        signInTrusting(phone, "td-list", enrolled, true);

        var mine = laptop.send("GET", "/api/v1/auth/mfa", null).body();

        assertThat((List<Object>) JsonPath.read(mine, "$.trustedDevices")).hasSize(2);
        assertThat((List<Boolean>) JsonPath.read(mine, "$.trustedDevices[*].current"))
                .containsExactlyInAnyOrder(true, false);
        assertThat((List<String>) JsonPath.read(mine, "$.trustedDevices[?(@.current == true)].client"))
                .containsExactly(AGENT);
        assertThat((String) JsonPath.read(mine, "$.trustedDevices[0].address")).startsWith("10.40.");
        Instant expires = Instant.parse(JsonPath.read(mine, "$.trustedDevices[0].expires"));
        assertThat(expires).isAfter(Instant.now().plusSeconds(29L * 86400));
        assertThat((String) JsonPath.read(mine, "$.trustedDevices[0].created")).isNotBlank();
        assertThat((String) JsonPath.read(mine, "$.trustedDevices[0].lastUsed")).isNotBlank();
    }

    @Test
    void revokingOneDeviceStopsItSkippingTheFactorAndLeavesTheOthers() throws Exception {
        Enrolled enrolled = newEnrolledUser("td-revoke-one");
        Browser laptop = browser();
        signInTrusting(laptop, "td-revoke-one", enrolled, true);
        Browser phone = browser();
        signInTrusting(phone, "td-revoke-one", enrolled, true);
        String phoneId = JsonPath.<List<String>>read(
                        phone.send("GET", "/api/v1/auth/mfa", null).body(), "$.trustedDevices[?(@.current == true)].id")
                .getFirst();

        var revoked = laptop.send("DELETE", "/api/v1/auth/mfa/trusted-devices/" + phoneId, null);

        assertThat(revoked.statusCode()).isEqualTo(204);
        assertThat(devices("td-revoke-one")).isEqualTo(1);
        phone.post("/api/v1/auth/logout", null);
        laptop.post("/api/v1/auth/logout", null);
        assertThat((String) JsonPath.read(login(phone, "td-revoke-one").body(), "$.status"))
                .isEqualTo("SECOND_FACTOR_REQUIRED");
        assertThat((String) JsonPath.read(login(laptop, "td-revoke-one").body(), "$.status"))
                .isEqualTo("AUTHENTICATED");
        assertThat(audited("TRUSTED_DEVICE_REVOKE", "td-revoke-one")).isEqualTo(1);
        // Someone else's device id is not found.
        assertThat(laptop.send("DELETE", "/api/v1/auth/mfa/trusted-devices/" + UUID.randomUUID(), null)
                        .statusCode())
                .isEqualTo(404);
    }

    @Test
    void revokingAllClearsTheCookieOfThisBrowserToo() throws Exception {
        Enrolled enrolled = newEnrolledUser("td-revoke-all");
        Browser laptop = browser();
        signInTrusting(laptop, "td-revoke-all", enrolled, true);
        signInTrusting(browser(), "td-revoke-all", enrolled, true);

        var revoked = laptop.send("DELETE", "/api/v1/auth/mfa/trusted-devices", null);

        assertThat(revoked.statusCode()).isEqualTo(204);
        assertThat(cookies(revoked)).hasSize(1).allMatch(c -> c.contains("Max-Age=0"));
        assertThat(devices("td-revoke-all")).isZero();
        assertThat((List<Object>) JsonPath.read(
                        laptop.send("GET", "/api/v1/auth/mfa", null).body(), "$.trustedDevices"))
                .isEmpty();
    }

    // ---- revocations -------------------------------------------------------------------------

    @Test
    void changingThePasswordRevokesTrustedDevices() throws Exception {
        Enrolled enrolled = newEnrolledUser("td-password");
        Browser browser = browser();
        signInTrusting(browser, "td-password", enrolled, true);

        var changed = browser.post(
                "/api/v1/auth/password",
                "{\"currentPassword\":\"%s\",\"newPassword\":\"Tr0ub4dor&3-horse-staple\"}".formatted(PASSWORD));

        assertThat(changed.statusCode()).isEqualTo(204);
        assertThat(devices("td-password")).isZero();
        assertThat(audited("TRUSTED_DEVICE_REVOKE", "td-password")).isEqualTo(1);
    }

    @Test
    void disablingTheAccountRevokesItsTrustedDevices() throws Exception {
        Enrolled enrolled = newEnrolledUser("td-disabled");
        signInTrusting(browser(), "td-disabled", enrolled, true);
        newAdministrator("td-admin");
        Browser admin = signedIn("td-admin");
        UUID id = users.findByUsername("td-disabled").orElseThrow().getId();

        var disabled = admin.send("PUT", "/api/v1/users/" + id + "/disabled", "{\"disabled\":true}");

        assertThat(disabled.statusCode()).isEqualTo(200);
        assertThat(devices("td-disabled")).isZero();
    }
}
