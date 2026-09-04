package io.github.sudoitir.artemisstudio.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

/**
 * The SPA fallback must not swallow API 404s: the client parses those as problem
 * details, so an HTML shell with a 200 would turn a missing endpoint into an
 * unreadable error. The forward-to-{@code index.html} half needs a built
 * frontend on the classpath ({@code -Pfrontend}), so it is covered by the
 * browser smoke run, not here.
 */
class SpaRoutingTest extends PostgresIntegrationTest {

    @Autowired
    WebApplicationContext webContext;

    @Test
    void unknownApiPathIsNotForwardedToTheSpa() throws Exception {
        MockMvc mvc = webAppContextSetup(webContext).build();
        mvc.perform(get("/api/v1/no-such-endpoint")).andExpect(status().isNotFound());
        mvc.perform(get("/actuator/no-such-endpoint")).andExpect(status().isNotFound());
    }
}
