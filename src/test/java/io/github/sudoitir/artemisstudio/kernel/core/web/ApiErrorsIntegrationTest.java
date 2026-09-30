package io.github.sudoitir.artemisstudio.kernel.core.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

/** Every failure through the real security chain and advice is a problem+json body (api-contract spec). */
@Import(ApiErrorsIntegrationTest.Boom.class)
class ApiErrorsIntegrationTest extends PostgresIntegrationTest {

    private static final String PROBLEM = "application/problem+json";
    private static final String TYPE = "https://artemis-studio.dev/problems/";

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
    void noCredentialsIs401Unauthenticated() throws Exception {
        mvc.perform(get("/api/v1/clusters"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.type").value(TYPE + "unauthenticated"));
    }

    @Test
    void badBearerTokenIs401Unauthenticated() throws Exception {
        mvc.perform(get("/api/v1/clusters").header("Authorization", "Bearer nope"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.type").value(TYPE + "unauthenticated"));
    }

    @Test
    void missingPermissionIs403() throws Exception {
        mvc.perform(delete("/api/v1/environments/00000000-0000-0000-0000-000000000000")
                        .with(user("nobody"))
                        .with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.type").exists());
    }

    @Test
    void missingCsrfTokenIs403Csrf() throws Exception {
        mvc.perform(post("/api/v1/environments")
                        .with(user("nobody"))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.type").value(TYPE + "csrf"));
    }

    @Test
    void unreadableBodyIs400BadRequest() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType("application/json")
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.type").value(TYPE + "bad-request"));
    }

    @Test
    void wrongMethodIs405() throws Exception {
        mvc.perform(get("/api/v1/auth/login").with(user("nobody")))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.type").value(TYPE + "method-not-allowed"));
    }

    @Test
    void unknownRouteIs404() throws Exception {
        mvc.perform(get("/api/v1/no-such-route").with(user("nobody")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.type").value(TYPE + "not-found"));
    }

    @Test
    void anUnsupportedApiVersionIs400InvalidApiVersion() throws Exception {
        for (String version : new String[] {"v2", "zzz"}) {
            mvc.perform(get("/api/" + version + "/clusters").with(user("nobody")))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                    .andExpect(jsonPath("$.type").value(TYPE + "invalid-api-version"));
        }
    }

    @Test
    void unexpectedFailureIs500AndHidesTheCause() throws Exception {
        mvc.perform(get("/api/v1/test/boom").with(user("nobody")))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.type").value(TYPE + "internal-error"))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))));
    }

    @RestController
    static class Boom {
        @GetMapping("/test/boom")
        String boom() {
            throw new IllegalStateException("secret internal detail");
        }
    }
}
