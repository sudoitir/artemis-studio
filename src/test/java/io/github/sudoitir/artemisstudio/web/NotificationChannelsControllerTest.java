package io.github.sudoitir.artemisstudio.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import io.github.sudoitir.artemisstudio.persist.NotificationChannelRepository;
import io.github.sudoitir.artemisstudio.support.AdminAuthenticationExtension;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

/** Channel CRUD — the secret is write-only and never returned in plaintext (alerting spec). */
@ExtendWith(AdminAuthenticationExtension.class)
class NotificationChannelsControllerTest extends PostgresIntegrationTest {

    MockMvc mvc;

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    NotificationChannelRepository channels;

    @BeforeEach
    void setUp() {
        mvc = webAppContextSetup(webContext).build();
    }

    @Test
    void createdChannelNeverExposesItsSecretOnRead() throws Exception {
        String name = "ops-" + java.util.UUID.randomUUID();
        String body = """
                {"name":"%s","kind":"SLACK","config":"{}","secret":"https://hooks.slack.com/services/x","enabled":true}""".formatted(name);

        String created = mvc.perform(post("/api/v1/channels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.hasSecret").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThatBodyNeverMentionsTheSecret(created);
        String id = created.split("\"id\":\"")[1].split("\"")[0];

        String listed = mvc.perform(get("/api/v1/channels"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThatBodyNeverMentionsTheSecret(listed);

        mvc.perform(delete("/api/v1/channels/{id}", id)).andExpect(status().isNoContent());
    }

    @Test
    void unknownKindIsRejected() throws Exception {
        String body = """
                {"name":"bad-%s","kind":"EMAIL","config":"{}","enabled":true}""".formatted(java.util.UUID.randomUUID());

        mvc.perform(post("/api/v1/channels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    private static void assertThatBodyNeverMentionsTheSecret(String body) {
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("hooks.slack.com/services/x");
    }
}
