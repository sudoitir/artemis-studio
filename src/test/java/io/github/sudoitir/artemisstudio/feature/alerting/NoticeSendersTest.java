package io.github.sudoitir.artemisstudio.feature.alerting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Every sender renders a notice payload as it renders an alert's: once, for its own channel kind. */
class NoticeSendersTest {

    private static final String URL = "https://receiver.example.com/hook";
    private static final String SECRET = "whsec_" + Base64.getEncoder().encodeToString("secret".getBytes());

    private final ObjectMapper mapper = JsonMapper.builder().build();

    private final String payload = """
            {"type":"notice","source":"approvals","kind":"notice","title":"Approval requested",
             "summary":"alice asks to change a setting","severity":"WARNING",
             "facts":[{"label":"Requested by","value":"alice"},{"label":"Cluster","value":"prod <1>"}],
             "url":"https://studio.example.com/approvals/3","sentAt":"2026-10-07T10:00:00Z"}""";

    private final String payloadWithoutLink =
            payload.replace("\"url\":\"https://studio.example.com/approvals/3\"", "\"url\":null");

    @Test
    void slackGetsAHeaderTheFactsAndAButton() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.blocks[0].text.text").value("[WARNING] Approval requested"))
                .andExpect(jsonPath("$.blocks[1].text.text").value("alice asks to change a setting"))
                .andExpect(jsonPath("$.blocks[2].fields[0].text").value("*Requested by*\nalice"))
                .andExpect(jsonPath("$.blocks[2].fields[1].text").value("*Cluster*\nprod &lt;1&gt;"))
                .andExpect(jsonPath("$.blocks[3].elements[0].text.text").value("Open in Studio"))
                .andExpect(jsonPath("$.blocks[3].elements[0].url").value("https://studio.example.com/approvals/3"))
                .andRespond(withStatus(HttpStatus.OK));

        assertThat(new SlackSender(builder.build(), mapper)
                        .send(1, "{}", URL, payload)
                        .success())
                .isTrue();
        server.verify();
    }

    @Test
    void slackLeavesTheButtonOutWithoutALink() {
        var blocks = SlackSender.blocks(NoticePayload.parseOrNull(payloadWithoutLink, mapper));

        assertThat(mapper.writeValueAsString(blocks)).doesNotContain("\"actions\"");
    }

    @Test
    void teamsGetsACardWithFactsAndALink() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.attachments[0].content.body[0].text").value("[WARNING] Approval requested"))
                .andExpect(jsonPath("$.attachments[0].content.body[1].text").value("alice asks to change a setting"))
                .andExpect(jsonPath("$.attachments[0].content.body[2].facts[0].title")
                        .value("Requested by"))
                .andExpect(jsonPath("$.attachments[0].content.body[2].facts[0].value")
                        .value("alice"))
                .andExpect(jsonPath("$.attachments[0].content.actions[0].url")
                        .value("https://studio.example.com/approvals/3"))
                .andRespond(withStatus(HttpStatus.ACCEPTED));

        assertThat(new TeamsSender(builder.build(), mapper)
                        .send(1, "{}", URL, payload)
                        .success())
                .isTrue();
        server.verify();
    }

    @Test
    void teamsLeavesTheActionOutWithoutALink() {
        var message = TeamsSender.message(NoticePayload.parseOrNull(payloadWithoutLink, mapper));

        assertThat(mapper.writeValueAsString(message)).doesNotContain("Action.OpenUrl");
    }

    @Test
    void aWebhookGetsTheNoticeSignedLikeAnAlert() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AtomicReference<String> seen = new AtomicReference<>();
        server.expect(requestTo(URL))
                .andExpect(header("webhook-id", "alert-delivery-7"))
                .andExpect(jsonPath("$.type").value("notice"))
                .andExpect(jsonPath("$.source").value("approvals"))
                .andExpect(jsonPath("$.kind").value("notice"))
                .andExpect(jsonPath("$.title").value("Approval requested"))
                .andExpect(jsonPath("$.facts[0].label").value("Requested by"))
                .andExpect(jsonPath("$.url").value("https://studio.example.com/approvals/3"))
                .andExpect(jsonPath("$.sentAt").value("2026-10-07T10:00:00Z"))
                .andExpect(request -> {
                    String timestamp = request.getHeaders().getFirst("webhook-timestamp");
                    String body =
                            ((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString();
                    seen.set(WebhookSigner.sign("alert-delivery-7", Long.parseLong(timestamp), body, SECRET));
                    assertThat(request.getHeaders().getFirst("webhook-signature"))
                            .isEqualTo(seen.get());
                })
                .andRespond(withStatus(HttpStatus.OK));

        assertThat(new WebhookSender(builder.build(), mapper)
                        .send(7, "{\"url\":\"" + URL + "\"}", SECRET, payload)
                        .success())
                .isTrue();
        server.verify();
        assertThat(seen.get()).startsWith("v1,");
    }

    @Test
    void pagerDutyGetsOneInfoEventKeyedByTheDelivery() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(PagerDutySender.DEFAULT_URL))
                .andExpect(jsonPath("$.event_action").value("trigger"))
                .andExpect(jsonPath("$.dedup_key").value("artemis-studio|notice|9"))
                .andExpect(jsonPath("$.payload.severity").value("info"))
                .andExpect(jsonPath("$.payload.summary").value("Approval requested"))
                .andExpect(jsonPath("$.payload.source").value("approvals"))
                .andExpect(jsonPath("$.payload.custom_details['Requested by']").value("alice"))
                .andExpect(jsonPath("$.links[0].href").value("https://studio.example.com/approvals/3"))
                .andRespond(withStatus(HttpStatus.ACCEPTED));

        assertThat(new PagerDutySender(builder.build(), mapper)
                        .send(9, "{}", "routing-key", payload)
                        .success())
                .isTrue();
        server.verify();
    }

    @Test
    void emailPutsTheTitleInTheSubjectAndTheFactsAndLinkInTheBody() throws Exception {
        var config = new EmailChannelConfig(
                "smtp.example.com",
                587,
                "STARTTLS",
                null,
                "studio@example.com",
                List.of("ops@example.com"),
                "[Artemis]");
        MimeMessage mime = new MimeMessage(Session.getInstance(new Properties()));

        EmailSender.compose(mime, config, NoticePayload.parseOrNull(payload, mapper), 5);
        mime.saveChanges();

        assertThat(mime.getSubject()).isEqualTo("[Artemis] Approval requested");
        assertThat(mime.getHeader("X-Artemis-Studio-Delivery")).containsExactly("5");
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        mime.writeTo(out);
        String raw = out.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(NoticeFormatter.plainText(NoticePayload.parseOrNull(payload, mapper)))
                .contains("alice asks to change a setting", "Requested by: alice")
                .contains("Open in Studio: https://studio.example.com/approvals/3");
        assertThat(NoticeFormatter.html(NoticePayload.parseOrNull(payload, mapper)))
                .contains("prod &lt;1&gt;")
                .contains("<a href=\"https://studio.example.com/approvals/3\">");
        assertThat(raw).contains("multipart/alternative");
    }

    @Test
    void anAlertPayloadIsNotANotice() {
        assertThat(NoticePayload.parseOrNull("{\"event\":\"alert.transitions\",\"transitions\":[]}", mapper))
                .isNull();
    }
}
