package io.github.sudoitir.artemisstudio.kernel.core.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A declared deprecation sends the RFC headers on the endpoint and flags it in the document. */
@Import(ApiDeprecationIntegrationTest.Declared.class)
class ApiDeprecationIntegrationTest extends PostgresIntegrationTest {

    @TestConfiguration
    static class Declared {
        @Bean
        ApiDeprecation deprecateTime() {
            return new ApiDeprecation(
                    "1",
                    HttpMethod.GET,
                    "/api/v1/time",
                    ZonedDateTime.of(2030, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC),
                    ZonedDateTime.of(2031, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC),
                    URI.create("https://artemis-studio.dev/deprecations/time"));
        }
    }

    @Autowired
    WebApplicationContext webContext;

    @Test
    void sendsDeprecationHeadersOnlyOnTheDeclaredEndpoint() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(webContext)
                .apply(springSecurity())
                .build();

        mvc.perform(get("/api/v1/time").with(user("nobody")))
                .andExpect(status().isOk())
                .andExpect(header().exists("Deprecation"))
                .andExpect(header().exists("Sunset"))
                .andExpect(header().string("Link", org.hamcrest.Matchers.containsString("rel=\"deprecation\"")));
        mvc.perform(get("/api/v1/manifest").with(user("nobody"))).andExpect(header().doesNotExist("Deprecation"));
    }

    @Test
    void marksTheOperationDeprecatedInTheDocument() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(webContext).build();
        JsonNode doc = JsonMapper.builder()
                .build()
                .readTree(mvc.perform(get("/v3/api-docs"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8));

        assertThat(doc.at("/paths/~1api~1v1~1time/get/deprecated").asBoolean()).isTrue();
        assertThat(doc.at("/paths/~1api~1v1~1manifest/get/deprecated").asBoolean())
                .isFalse();
    }
}
