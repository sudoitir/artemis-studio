package io.github.sudoitir.artemisstudio.feature.alerting.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import io.github.sudoitir.artemisstudio.feature.alerting.internal.persistence.NotificationChannelRepository;
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
                {"name":"bad-%s","kind":"SMS","config":"{}","enabled":true}""".formatted(java.util.UUID.randomUUID());

        mvc.perform(post("/api/v1/channels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anEmailChannelIsValidatedPerFieldAndReportsItsBoundRules() throws Exception {
        String name = "mail-" + java.util.UUID.randomUUID();
        String noRecipients = """
                {"name":"%s","kind":"EMAIL","config":"{\\"host\\":\\"smtp.example.com\\",\\"port\\":587,\\"security\\":\\"STARTTLS\\",\\"from\\":\\"a@example.com\\",\\"to\\":[]}","enabled":true}""".formatted(name);
        mvc.perform(post("/api/v1/channels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(noRecipients))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("to:")));

        String valid = noRecipients.replace("[]", "[\\\"oncall@example.com\\\"]");
        String created = mvc.perform(post("/api/v1/channels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(valid))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.boundRuleCount").value(0))
                .andExpect(jsonPath("$.hasSecret").value(false))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String id = created.split("\"id\":\"")[1].split("\"")[0];

        mvc.perform(get("/api/v1/channels/{id}/deliveries", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        mvc.perform(post("/api/v1/channels/{id}/deliveries/{seq}/retry", id, 999999999L))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/channels/{id}", id)).andExpect(status().isNoContent());
    }

    @Test
    void aFailedTestOfAnUnsavedConfigurationIsAResultNotAnError() throws Exception {
        // Port 9 (discard) on loopback: nothing listens, so the connection is refused at once.
        String body = """
                {"kind":"TEAMS","config":"{}","secret":"http://127.0.0.1:9/hook"}""";
        mvc.perform(post("/api/v1/channels/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivered").value(false))
                .andExpect(jsonPath("$.permanent").value(false))
                .andExpect(jsonPath("$.error").isNotEmpty());

        String invalid = """
                {"kind":"PAGERDUTY","config":"{}","secret":"too-short"}""";
        mvc.perform(post("/api/v1/channels/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalid))
                .andExpect(status().isBadRequest());
    }

    private static void assertThatBodyNeverMentionsTheSecret(String body) {
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("hooks.slack.com/services/x");
    }
}
