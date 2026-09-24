package io.github.sudoitir.artemisstudio.feature.alerting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.ObjectMapper;

/**
 * Delivers to a Slack incoming webhook (ADR-0036). The channel's secret <em>is</em>
 * the webhook URL — Slack has no separate signing step. The message is Block Kit rendered from
 * {@link AlertMessageFormatter} (ADR-0105). A {@code 404 no_team}
 * means the webhook was revoked and is never retried; a {@code 429} honours
 * {@code Retry-After}.
 */
@Component
@Slf4j
public class SlackSender implements NotificationSender {

    private final RestClient restClient;
    private final ObjectMapper mapper;

    public SlackSender(@Qualifier("notificationRestClient") RestClient restClient, ObjectMapper mapper) {
        this.restClient = restClient;
        this.mapper = mapper;
    }

    @Override
    public String kind() {
        return "SLACK";
    }

    @Override
    public Result send(long deliveryId, String channelConfigJson, String webhookUrl, String payloadJson) {
        String body;
        try {
            body = mapper.writeValueAsString(blocks(AlertMessage.parse(payloadJson, mapper)));
        } catch (RuntimeException e) {
            log.warn("Failed to render alert payload for Slack: {}", e.toString());
            return Result.permanent("The alert payload could not be rendered: " + e.getMessage());
        }
        try {
            restClient
                    .post()
                    .uri(webhookUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return Result.ok();
        } catch (HttpClientErrorException.NotFound e) {
            return Result.permanent("Slack webhook not found — likely revoked: " + e.getResponseBodyAsString());
        } catch (HttpClientErrorException.TooManyRequests e) {
            return Result.retryable("Slack rate limited", RetryAfter.parse(e.getResponseHeaders()));
        } catch (HttpStatusCodeException e) {
            return Result.retryable("Slack responded " + e.getStatusCode());
        } catch (RestClientException e) {
            return Result.retryable(e.getMessage());
        }
    }

    /**
     * Block Kit: the title as a header, the facts as fields, one line per subject, and a button
     * back to Studio when there is a link. {@code text} is the notification fallback.
     */
    static Map<String, Object> blocks(AlertMessage m) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(Map.of("type", "header", "text", plain(truncate(AlertMessageFormatter.title(m), 150))));
        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(mrkdwn("*Severity*\n" + AlertMessageFormatter.severityWord(m.severity())));
        fields.add(mrkdwn("*Rule*\n" + escape(m.ruleName())));
        if (m.clusterName() != null) {
            fields.add(mrkdwn("*Cluster*\n" + escape(m.clusterName())));
        }
        blocks.add(Map.of("type", "section", "fields", fields));
        StringBuilder lines = new StringBuilder();
        for (AlertMessage.Line t : m.transitions()) {
            lines.append("• ").append(escape(AlertMessageFormatter.line(t))).append('\n');
        }
        blocks.add(Map.of("type", "section", "text", mrkdwn(truncate(lines.toString(), 2900))));
        if (m.studioUrl() != null) {
            blocks.add(Map.of(
                    "type",
                    "actions",
                    "elements",
                    List.of(Map.of("type", "button", "text", plain("Open in Studio"), "url", m.studioUrl()))));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", AlertMessageFormatter.title(m));
        body.put("blocks", blocks);
        return body;
    }

    private static Map<String, Object> plain(String text) {
        return Map.of("type", "plain_text", "text", text);
    }

    private static Map<String, Object> mrkdwn(String text) {
        return Map.of("type", "mrkdwn", "text", text);
    }

    /** Slack's three control characters in mrkdwn. */
    static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
