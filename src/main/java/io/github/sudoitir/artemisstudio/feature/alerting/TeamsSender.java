package io.github.sudoitir.artemisstudio.feature.alerting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.ObjectMapper;

/**
 * Delivers to a Microsoft Teams webhook as an Adaptive Card (ADR-0105 D4). The channel's secret is
 * the webhook URL. Workflows (Power Automate) webhooks and the retiring Office 365 connector
 * webhooks both accept this message shape; the legacy MessageCard does not render in Workflows.
 *
 * <p>A response saying the flow is gone or the URL is not authorised is permanent — retrying a
 * deleted workflow for an hour tells the operator nothing the first failure did not.
 */
@Component
public class TeamsSender implements NotificationSender {

    /** Statuses retrying cannot fix: a malformed card, a revoked or deleted flow. */
    private static final Set<Integer> PERMANENT = Set.of(400, 401, 403, 404, 410);

    private final RestClient restClient;
    private final ObjectMapper mapper;

    public TeamsSender(@Qualifier("notificationRestClient") RestClient restClient, ObjectMapper mapper) {
        this.restClient = restClient;
        this.mapper = mapper;
    }

    @Override
    public String kind() {
        return "TEAMS";
    }

    @Override
    public Result send(long deliveryId, String channelConfigJson, String webhookUrl, String payloadJson) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return Result.permanent("Teams channel has no webhook URL");
        }
        String body;
        try {
            body = mapper.writeValueAsString(message(AlertMessage.parse(payloadJson, mapper)));
        } catch (RuntimeException e) {
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
        } catch (HttpClientErrorException.TooManyRequests e) {
            return Result.retryable("Teams rate limited", RetryAfter.parse(e.getResponseHeaders()));
        } catch (HttpStatusCodeException e) {
            String reason = "Teams responded " + e.getStatusCode();
            return PERMANENT.contains(e.getStatusCode().value())
                    ? Result.permanent(reason + " — check that the workflow still exists and the URL is complete")
                    : Result.retryable(reason);
        } catch (RestClientException e) {
            return Result.retryable(e.getMessage());
        }
    }

    /** {@code {type: message, attachments: [{contentType: adaptive card, content: card}]}}. */
    static Map<String, Object> message(AlertMessage m) {
        List<Object> bodyItems = new ArrayList<>();
        bodyItems.add(Map.of(
                "type",
                "TextBlock",
                "text",
                AlertMessageFormatter.title(m),
                "weight",
                "Bolder",
                "size",
                "Medium",
                "wrap",
                true,
                // Emphasis only: the severity is already the first word of the title.
                "color",
                switch (AlertMessageFormatter.severityWord(m.severity())) {
                    case "CRITICAL" -> "Attention";
                    case "WARNING" -> "Warning";
                    default -> "Default";
                }));
        List<Map<String, Object>> facts = new ArrayList<>();
        facts.add(Map.of("title", "Severity", "value", AlertMessageFormatter.severityWord(m.severity())));
        facts.add(Map.of("title", "Rule", "value", m.ruleName()));
        if (m.clusterName() != null) {
            facts.add(Map.of("title", "Cluster", "value", m.clusterName()));
        }
        bodyItems.add(Map.of("type", "FactSet", "facts", facts));
        for (AlertMessage.Line t : m.transitions()) {
            bodyItems.add(Map.of("type", "TextBlock", "text", "• " + AlertMessageFormatter.line(t), "wrap", true));
        }

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("$schema", "http://adaptivecards.io/schemas/adaptive-card.json");
        card.put("type", "AdaptiveCard");
        card.put("version", "1.4");
        card.put("body", bodyItems);
        if (m.studioUrl() != null) {
            card.put(
                    "actions",
                    List.of(Map.of("type", "Action.OpenUrl", "title", "Open in Studio", "url", m.studioUrl())));
        }
        return Map.of(
                "type",
                "message",
                "attachments",
                List.of(Map.of("contentType", "application/vnd.microsoft.card.adaptive", "content", card)));
    }
}
