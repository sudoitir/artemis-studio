package io.github.sudoitir.artemisstudio.feature.apitokens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jayway.jsonpath.JsonPath;
import io.github.sudoitir.artemisstudio.kernel.security.Grant;
import io.github.sudoitir.artemisstudio.kernel.security.ScopeIds;
import io.github.sudoitir.artemisstudio.kernel.security.SecondFactorRequiredException;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.artemisstudio.support.AccountIntegrationTest;
import io.github.sudoitir.artemisstudio.support.Browser;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Personal API tokens and second factors (ADR-0142, D7): a token is minted from a session, records
 * whether that session had verified a second factor, and stops authenticating when its owner comes to
 * require one it was minted without. Bearer streams end when their token stops being accepted.
 */
class TokenMfaIntegrationTest extends AccountIntegrationTest {

    @Autowired
    ApiTokenService tokens;

    private static Instant inAnHour() {
        return Instant.now().plusSeconds(3600);
    }

    private static String mintRequest(String name) {
        return "{\"name\":\"%s\",\"expiresAt\":\"%s\",\"grants\":[{\"action\":\"cluster:read\",\"scopeType\":\"GLOBAL\"}]}"
                .formatted(name, inAnHour());
    }

    /** The value of a token minted through the API by this browser. */
    private String mint(Browser browser, String name) throws Exception {
        var created = browser.post("/api/v1/tokens", mintRequest(name));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return JsonPath.read(created.body(), "$.value");
    }

    private int asToken(String token, String path) throws Exception {
        return browser()
                .send("GET", path, null, "Authorization", "Bearer " + token)
                .statusCode();
    }

    @Test
    void aTokenIsMintedFromASessionAndRecordsWhetherItsSecondFactorWasVerified() throws Exception {
        newAdministrator("tk-record");
        Browser plain = signedIn("tk-record");
        String withoutMfa = mint(plain, "plain");
        Enrolled enrolled = enrol(plain);
        Browser verified = signedInWithSecondFactor("tk-record", enrolled);
        String withMfa = mint(verified, "verified");

        assertThat(jdbc.sql("SELECT name, minted_with_mfa FROM api_token WHERE user_id ="
                                + " (SELECT id FROM app_user WHERE username = 'tk-record') ORDER BY name")
                        .query((rs, row) -> rs.getString(1) + "=" + rs.getBoolean(2))
                        .list())
                .containsExactly("plain=false", "verified=true");
        assertThat(asToken(withoutMfa, ME)).isEqualTo(200); // the owner does not require a second factor
        assertThat(asToken(withMfa, ME)).isEqualTo(200);
    }

    @Test
    void aTokenCannotMintAnotherToken() throws Exception {
        newAdministrator("tk-chain");
        String token = mint(signedIn("tk-chain"), "first");

        var response =
                browser().send("POST", "/api/v1/tokens", mintRequest("second"), "Authorization", "Bearer " + token);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(problem(response)).endsWith("/session-required");
        assertThat((String) JsonPath.read(response.body(), "$.detail")).contains("Sign in");
    }

    @Test
    void aTokenMintedWithoutMfaStopsWorkingOnceItsOwnerRequiresOne() throws Exception {
        UUID id = newAdministrator("tk-required-later");
        String token = mint(signedIn("tk-required-later"), "old");
        assertThat(asToken(token, ME)).isEqualTo(200);

        requireMfa(id);

        assertThat(asToken(token, ME)).isEqualTo(401);
    }

    @Test
    void aTokenMintedAfterVerifyingASecondFactorKeepsWorkingWhenItsOwnerRequiresOne() throws Exception {
        UUID id = newAdministrator("tk-verified");
        Enrolled enrolled = enrol(signedIn("tk-verified"));
        String token = mint(signedInWithSecondFactor("tk-verified", enrolled), "verified");

        requireMfa(id);

        assertThat(asToken(token, ME)).isEqualTo(200);
    }

    @Test
    void aRequiredUserWhoHasNotFinishedEnrolmentCannotMint() throws Exception {
        UUID id = newUser("tk-restricted");
        requireMfa(id);
        Browser browser = signedIn("tk-restricted");

        var response = browser.post("/api/v1/tokens", mintRequest("early"));

        assertThat(response.statusCode()).isEqualTo(423); // the session may only enrol
        assertThat(jdbc.sql("SELECT count(*) FROM api_token WHERE user_id = ?")
                        .param(id)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void mintingChecksTheSecondFactorItselfToo() throws Exception {
        UUID id = newUser("tk-direct");
        requireMfa(id);
        Grant grant = new Grant(Grant.ScopeType.GLOBAL, ScopeIds.GLOBAL, Set.of("cluster:read"));

        assertThatThrownBy(() -> tokens.mint(id, "sneaky", inAnHour(), List.of(grant), List.of(), false))
                .isInstanceOf(SecondFactorRequiredException.class);
        assertThat(tokens.mint(id, "verified", inAnHour(), List.of(grant), List.of(), true)
                        .plaintext())
                .isNotBlank();
    }

    @Test
    void aSingleSignOnUserIsNotChallengedForASecondFactor() throws Exception {
        AppUserEntity sso = users.save(AppUserEntity.external("oidc-corp", "subject-1", "tk-sso", "sso@example.test"));
        requireMfa(sso.getId());
        Grant grant = new Grant(Grant.ScopeType.GLOBAL, ScopeIds.GLOBAL, Set.of("cluster:read"));

        String token = tokens.mint(sso.getId(), "sso", inAnHour(), List.of(grant), List.of(), false)
                .plaintext();

        assertThat(asToken(token, ME)).isEqualTo(200);
    }
}
