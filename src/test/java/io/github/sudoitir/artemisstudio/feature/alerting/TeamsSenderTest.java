package io.github.sudoitir.artemisstudio.feature.alerting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class TeamsSenderTest {

    private static final String URL =
            "https://prod.westeurope.logic.azure.com/workflows/abc/triggers/manual/paths/invoke";

    private final ObjectMapper mapper = JsonMapper.builder().build();

    private final String payload = """
            {"ruleId":"%s","ruleName":"Split-brain","severity":"CRITICAL","clusterName":"prod",
             "studioUrl":"https://studio/clusters/x/alerts",
             "transitions":[{"subject":"cluster","subjectLabel":"cluster prod","kind":"FIRED","value":1}]}""".formatted(UUID.randomUUID());

    @Test
    void sendsAnAdaptiveCardWithTheSeverityInWordsAndALink() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.type").value("message"))
                .andExpect(jsonPath("$.attachments[0].contentType").value("application/vnd.microsoft.card.adaptive"))
                .andExpect(jsonPath("$.attachments[0].content.body[0].text")
                        .value("[CRITICAL] Split-brain — prod: 1 firing"))
                .andExpect(
                        jsonPath("$.attachments[0].content.actions[0].url").value("https://studio/clusters/x/alerts"))
                .andRespond(withStatus(HttpStatus.ACCEPTED));

        assertThat(new TeamsSender(builder.build(), mapper)
                        .send(1, "{}", URL, payload)
                        .success())
                .isTrue();
        server.verify();
    }

    @Test
    void aDeletedWorkflowIsNotRetried() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        var result = new TeamsSender(builder.build(), mapper).send(1, "{}", URL, payload);

        assertThat(result.success()).isFalse();
        assertThat(result.permanent()).isTrue();
    }

    @Test
    void aServerErrorIsRetried() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        var result = new TeamsSender(builder.build(), mapper).send(1, "{}", URL, payload);

        assertThat(result.permanent()).isFalse();
    }
}
