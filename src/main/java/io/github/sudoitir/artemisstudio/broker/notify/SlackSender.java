package io.github.sudoitir.artemisstudio.broker.notify;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.ObjectMapper;

/**
 * Delivers to a Slack incoming webhook (ADR-0036). The channel's secret <em>is</em>
 * the webhook URL — Slack has no separate signing step. A {@code 404 no_team}
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
        String text = summarize(payloadJson);
        String body = mapper.writeValueAsString(Map.of(
                "text",
                text,
                "blocks",
                List.of(Map.of("type", "section", "text", Map.of("type", "mrkdwn", "text", text)))));
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
            return Result.retryable("Slack rate limited", retryAfter(e.getResponseHeaders()));
        } catch (HttpStatusCodeException e) {
            return Result.retryable("Slack responded " + e.getStatusCode());
        } catch (RestClientException e) {
            return Result.retryable(e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private String summarize(String payloadJson) {
        try {
            Map<String, Object> payload = mapper.readValue(payloadJson, Map.class);
            String ruleName = String.valueOf(payload.get("ruleName"));
            String severity = String.valueOf(payload.get("severity"));
            List<Map<String, Object>> transitions = (List<Map<String, Object>>) payload.get("transitions");
            StringBuilder sb = new StringBuilder("*[" + severity + "] " + ruleName + "*\n");
            for (Map<String, Object> t : transitions) {
                sb.append("• ")
                        .append(t.get("subject"))
                        .append(": ")
                        .append(t.get("kind"))
                        .append(t.get("value") != null ? " (" + t.get("value") + ")" : "")
                        .append("\n");
            }
            return sb.toString();
        } catch (RuntimeException e) {
            log.warn("Failed to summarize alert payload for Slack: {}", e.toString());
            return payloadJson;
        }
    }

    private static Duration retryAfter(HttpHeaders headers) {
        List<String> values = headers != null ? headers.get(HttpHeaders.RETRY_AFTER) : null;
        if (values == null || values.isEmpty()) {
            return null;
        }
        try {
            return Duration.ofSeconds(Long.parseLong(values.get(0).trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
