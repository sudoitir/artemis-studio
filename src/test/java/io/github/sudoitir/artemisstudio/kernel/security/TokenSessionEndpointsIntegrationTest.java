package io.github.sudoitir.artemisstudio.kernel.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.github.sudoitir.artemisstudio.support.AccountIntegrationTest;
import io.github.sudoitir.artemisstudio.support.Browser;
import org.junit.jupiter.api.Test;

/**
 * An API key acts within the grants it was narrowed to, so it cannot look at or end the sessions of
 * the person who owns it: a leaked read-only key would otherwise sign its owner out everywhere.
 */
class TokenSessionEndpointsIntegrationTest extends AccountIntegrationTest {

    @Test
    void aTokenCanNeitherListNorEndItsOwnersSessions() throws Exception {
        newAdministrator("tse-owner");
        Browser session = signedIn("tse-owner");
        Browser other = signedIn("tse-owner");
        String token = JsonPath.read(
                session.post("/api/v1/tokens", mintBody("sessions")).body(), "$.value");
        Browser bearer = browser();
        String bearerAuth = "Bearer " + token;

        var list = bearer.send("GET", "/api/v1/auth/sessions", null, "Authorization", bearerAuth);
        var endOthers = bearer.send("DELETE", "/api/v1/auth/sessions", null, "Authorization", bearerAuth);
        var endOne = bearer.send("DELETE", "/api/v1/auth/sessions/abc", null, "Authorization", bearerAuth);

        for (var refused : new java.net.http.HttpResponse<?>[] {list, endOthers, endOne}) {
            assertThat(refused.statusCode()).isEqualTo(403);
            assertThat(JsonPath.<String>read(refused.body().toString(), "$.type"))
                    .endsWith("/session-required");
        }
        assertThat(session.status("GET", CONSOLE)).isEqualTo(200);
        assertThat(other.status("GET", CONSOLE)).isEqualTo(200);
    }
}
