package io.github.sudoitir.artemisstudio.kernel.security;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Every response carries Studio's Content-Security-Policy (identity-and-sessions spec, ADR-0122):
 * the SPA shell, an API answer and a refusal alike, so no path is left frameable or open to
 * scripts from another origin.
 */
class SecurityHeadersTest extends PostgresIntegrationTest {

    @Autowired
    WebApplicationContext webContext;

    @Test
    void everyResponseCarriesTheContentSecurityPolicy() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .apply(springSecurity())
                .build();
        for (String path : new String[] {"/", "/api/v1/auth/providers", "/api/v1/clusters"}) {
            mvc.perform(get(path))
                    .andExpect(header().string(
                                    "Content-Security-Policy",
                                    Matchers.allOf(
                                            Matchers.containsString("script-src 'self' 'wasm-unsafe-eval';"),
                                            Matchers.containsString("frame-ancestors 'none'"),
                                            Matchers.containsString("object-src 'none'"))));
        }
    }
}
