package io.github.sudoitir.artemisstudio.kernel.security;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Every response carries Studio's security headers (identity-and-sessions spec, ADR-0122, ADR-0168):
 * the SPA shell, an API answer and a refusal alike, so no path is left frameable, open to scripts
 * from another origin, or leaking where the viewer came from.
 */
class SecurityHeadersTest extends PostgresIntegrationTest {

    /** The paths that between them are every kind of response Studio sends: shell, API, refusal, error. */
    private static final String[] PATHS = {"/", "/index.html", "/api/v1/auth/providers", "/api/v1/clusters", "/error"};

    @Autowired
    WebApplicationContext webContext;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    void everyResponseCarriesTheContentSecurityPolicy() throws Exception {
        for (String path : PATHS) {
            mvc.perform(get(path))
                    .andExpect(header().string(
                                    "Content-Security-Policy",
                                    Matchers.allOf(
                                            Matchers.containsString("script-src 'self' 'wasm-unsafe-eval';"),
                                            Matchers.containsString("script-src-attr 'none'"),
                                            Matchers.containsString("frame-ancestors 'none'"),
                                            Matchers.containsString("object-src 'none'"),
                                            Matchers.containsString("require-trusted-types-for 'script'"),
                                            Matchers.endsWith("trusted-types default dompurify studio#worker"))));
        }
    }

    @Test
    void everyResponseCarriesTheOtherSecurityHeaders() throws Exception {
        for (String path : PATHS) {
            expectSecurityHeaders(mvc.perform(get(path)));
        }
    }

    @Test
    void anErrorIsAProblemAndNeverAPageThatEchoesTheRequest() throws Exception {
        mvc.perform(get("/error?message=<script>alert(1)</script>"))
                .andExpect(status().isInternalServerError())
                .andExpect(
                        header().string("Content-Type", Matchers.startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE)))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(Matchers.not(Matchers.containsString("<script>"))));
        mvc.perform(get("/api/v1/<script>alert(1)</script>"))
                .andExpect(header().string("Content-Type", Matchers.not(Matchers.containsStringIgnoringCase("html"))));
    }

    @Test
    void strictTransportSecurityIsSentOverHttpsOnly() throws Exception {
        mvc.perform(get("/")).andExpect(header().doesNotExist("Strict-Transport-Security"));
        mvc.perform(get("/").secure(true))
                .andExpect(header().string("Strict-Transport-Security", Matchers.containsString("max-age=")));
    }

    private static void expectSecurityHeaders(ResultActions result) throws Exception {
        result.andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Cross-Origin-Opener-Policy", "same-origin"))
                .andExpect(header().string("Cross-Origin-Resource-Policy", "same-origin"))
                .andExpect(header().string(
                                "Permissions-Policy",
                                Matchers.allOf(
                                        Matchers.containsString("camera=()"),
                                        Matchers.containsString("microphone=()"),
                                        Matchers.containsString("geolocation=()"),
                                        Matchers.containsString("payment=()"),
                                        Matchers.containsString("usb=()"))));
    }
}
