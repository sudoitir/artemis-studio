package io.github.sudoitir.artemisstudio.feature.identitylocal;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.artemisstudio.support.AccountIntegrationTest;
import io.github.sudoitir.artemisstudio.support.Browser;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Break-glass recovery of a local account at startup (ADR-0143, D10): what it clears, what it forces,
 * what it audits and logs, and that a name it cannot recover is reported without stopping Studio.
 */
@ExtendWith(OutputCaptureExtension.class)
class AccountRecoveryIntegrationTest extends AccountIntegrationTest {

    @Autowired
    AccountRecovery recovery;

    @Test
    void recoveringAnAccountClearsItsFactorsAndLockForcesAPasswordChangeAndEndsItsSessions(CapturedOutput output)
            throws Exception {
        newAdministrator("ar-admin");
        Enrolled enrolled = enrol(signedIn("ar-admin"));
        Browser trusted = browser();
        login(trusted, "ar-admin");
        trusted.post(
                "/api/v1/auth/second-factor",
                "{\"totpCode\":\"%s\",\"trustDevice\":true}".formatted(freshCode("ar-admin", enrolled)));
        assertThat(trusted.status("GET", CONSOLE)).isEqualTo(200);
        jdbc.sql("UPDATE app_user SET locked_until = now() + interval '15 minutes', failed_login_count = 10"
                        + " WHERE username = 'ar-admin'")
                .update();

        recovery.recover("ar-admin");

        AppUserEntity user = users.findByUsername("ar-admin").orElseThrow();
        assertThat(user.getLockedUntil()).isNull();
        assertThat(user.getFailedLoginCount()).isZero();
        assertThat(user.isMustChangePassword()).isTrue();
        for (String table : List.of("local_totp", "local_recovery_code", "local_trusted_device")) {
            assertThat(jdbc.sql("SELECT count(*) FROM " + table + " WHERE user_id = ?")
                            .param(user.getId())
                            .query(Long.class)
                            .single())
                    .as(table)
                    .isZero();
        }
        assertThat(trusted.status("GET", CONSOLE)).as("its sessions ended").isEqualTo(401);
        assertThat(audited("ACCOUNT_RECOVER", "ar-admin")).isEqualTo(1);
        assertThat(output.getOut()).contains("ar-admin").contains("remove artemis-studio.identity-local.recover");
        // The administrator signs in with the password, and can only change it.
        Browser next = browser();
        var signIn = login(next, "ar-admin");
        assertThat((String) JsonPath.read(signIn.body(), "$.status")).isEqualTo("AUTHENTICATED");
        assertThat((Boolean) JsonPath.read(signIn.body(), "$.me.mustChangePassword"))
                .isTrue();
        assertThat(problem(next.send("GET", CONSOLE, null))).endsWith("/must-change-password");
    }

    @Test
    void recoveringAnAccountRevokesItsApiTokens() throws Exception {
        newAdministrator("ar-tokens");
        var minted = signedIn("ar-tokens").post("/api/v1/tokens", mintBody("recovered"));
        assertThat(minted.statusCode()).isEqualTo(201);
        String token = JsonPath.read(minted.body(), "$.value");
        assertThat(browser()
                        .send("GET", ME, null, "Authorization", "Bearer " + token)
                        .statusCode())
                .isEqualTo(200);

        recovery.recover("ar-tokens");

        assertThat(browser()
                        .send("GET", ME, null, "Authorization", "Bearer " + token)
                        .statusCode())
                .as("the token stopped working")
                .isEqualTo(401);
        assertThat(jdbc.sql("SELECT count(*) FROM api_token WHERE user_id = ? AND revoked_at IS NULL")
                        .param(users.findByUsername("ar-tokens").orElseThrow().getId())
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void aNameThatIsNotAnAccountIsReportedAndNothingCrashes(CapturedOutput output) {
        recovery.recover("ar-nobody");

        assertThat(output.getOut() + output.getErr()).contains("ar-nobody").contains("no local account");
        assertThat(audited("ACCOUNT_RECOVER", "ar-nobody")).isZero();
    }

    @Test
    void anAccountOfAnotherProviderIsNotRecovered(CapturedOutput output) {
        users.save(AppUserEntity.external("oidc-corp", "subject-7", "ar-sso", "sso@example.test"));

        recovery.recover("ar-sso");

        assertThat(output.getOut() + output.getErr()).contains("ar-sso").contains("no local account");
        assertThat(users.findByUsername("ar-sso").orElseThrow().isMustChangePassword())
                .isFalse();
    }

    @Test
    void nothingHappensWhenNoAccountIsNamed(CapturedOutput output) {
        recovery.recover("");

        assertThat(output.getOut()).doesNotContain("recovered");
    }
}
