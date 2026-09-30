package io.github.sudoitir.artemisstudio.feature.apitokens;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.github.sudoitir.artemisstudio.support.AccountIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * A call authenticated with an API token has no session: it carries no cookie to keep one for, and a
 * session per call would fill the session store and hand every caller a {@code SESSION} cookie.
 * Over real HTTP against the JDBC session store.
 */
class TokenSessionIntegrationTest extends AccountIntegrationTest {

    @Test
    void aTokenCallCreatesNoSession() throws Exception {
        newAdministrator("ts-stateless");
        var created = signedIn("ts-stateless").post("/api/v1/tokens", mintBody("stateless"));
        String token = JsonPath.read(created.body(), "$.value");
        long before = jdbc.sql("SELECT count(*) FROM spring_session")
                .query(Long.class)
                .single();

        var response = browser().send("GET", ME, null, "Authorization", "Bearer " + token);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().allValues("Set-Cookie")).noneMatch(c -> c.startsWith("SESSION="));
        assertThat(jdbc.sql("SELECT count(*) FROM spring_session")
                        .query(Long.class)
                        .single())
                .isEqualTo(before);
    }
}
