package io.github.sudoitir.artemisstudio.feature.alerting;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Delivers to the PagerDuty Events API v2, or any receiver compatible with it (ADR-0105 D3). The
 * channel's secret is the routing (integration) key.
 *
 * <p>One event per transition: {@code trigger} when a subject fires, {@code resolve} when it
 * clears, both carrying {@link #dedupKey}, so each firing subject is one incident that opens and
 * closes with the alert. A retry resends the whole delivery; that is safe <em>only</em> because
 * PagerDuty deduplicates a trigger on an open key and ignores a resolve on a closed one. Do not
 * change the key's derivation: an existing incident would never resolve.
 */
@Component
public class PagerDutySender implements NotificationSender {

    public static final String DEFAULT_URL = "https://events.pagerduty.com/v2/enqueue";

    private static final int SUMMARY_MAX = 1024;

    private final RestClient restClient;
    private final ObjectMapper mapper;

    public PagerDutySender(@Qualifier("notificationRestClient") RestClient restClient, ObjectMapper mapper) {
        this.restClient = restClient;
        this.mapper = mapper;
    }

    @Override
    public String kind() {
        return "PAGERDUTY";
    }

    @Override
    public Result send(long deliveryId, String channelConfigJson, String routingKey, String payloadJson) {
        if (routingKey == null || routingKey.isBlank()) {
            return Result.permanent("PagerDuty channel has no routing key");
        }
        String url = endpoint(channelConfigJson, mapper);
        AlertMessage m;
        try {
            m = AlertMessage.parse(payloadJson, mapper);
        } catch (RuntimeException e) {
            return Result.permanent("The alert payload could not be rendered: " + e.getMessage());
        }
        int sent = 0;
        for (AlertMessage.Line line : m.transitions()) {
            Result result = post(url, event(routingKey, m, line));
            if (!result.success()) {
                String prefix = sent > 0 ? sent + " of " + m.transitions().size() + " events accepted, then: " : "";
                return new Result(false, result.permanent(), prefix + result.error(), result.retryAfter());
            }
            sent++;
        }
        return Result.ok();
    }

    private Result post(String url, Map<String, Object> event) {
        try {
            restClient
                    .post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(mapper.writeValueAsString(event))
                    .retrieve()
                    .toBodilessEntity();
            return Result.ok();
        } catch (HttpClientErrorException.TooManyRequests e) {
            return Result.retryable("PagerDuty rate limited", RetryAfter.parse(e.getResponseHeaders()));
        } catch (HttpClientErrorException.BadRequest e) {
            return Result.permanent("PagerDuty rejected the event: " + e.getResponseBodyAsString());
        } catch (HttpStatusCodeException e) {
            int status = e.getStatusCode().value();
            String reason = "PagerDuty responded " + e.getStatusCode();
            // 401/403/404 mean a wrong key or endpoint; the next attempt would say the same.
            return status == 401 || status == 403 || status == 404
                    ? Result.permanent(reason + " — check the routing key and endpoint")
                    : Result.retryable(reason);
        } catch (RestClientException e) {
            return Result.retryable(e.getMessage());
        }
    }

    static Map<String, Object> event(String routingKey, AlertMessage m, AlertMessage.Line line) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("routing_key", routingKey);
        event.put("event_action", line.fired() ? "trigger" : "resolve");
        event.put("dedup_key", dedupKey(m, line));
        if (line.fired()) {
            String summary = AlertMessageFormatter.singleLine("["
                    + AlertMessageFormatter.severityWord(m.severity()) + "] " + m.ruleName() + " — " + line.label()
                    + (m.clusterName() != null ? " (" + m.clusterName() + ")" : ""));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("summary", summary.length() <= SUMMARY_MAX ? summary : summary.substring(0, SUMMARY_MAX));
            payload.put("source", m.clusterName() != null ? m.clusterName() : "artemis-studio");
            payload.put("severity", severity(m.severity()));
            if (line.at() != null) {
                payload.put("timestamp", line.at().toString());
            }
            payload.put("component", line.label());
            if (m.clusterName() != null) {
                payload.put("group", m.clusterName());
            }
            payload.put("class", m.ruleName());
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("rule", m.ruleName());
            details.put("subject", line.subject());
            if (line.value() != null) {
                details.put("value", line.value());
            }
            if (m.clusterId() != null) {
                details.put("clusterId", m.clusterId().toString());
            }
            payload.put("custom_details", details);
            event.put("payload", payload);
            if (m.studioUrl() != null) {
                event.put("links", java.util.List.of(Map.of("href", m.studioUrl(), "text", "Open in Studio")));
            }
            event.put("client", "Artemis Studio");
            if (m.studioUrl() != null) {
                event.put("client_url", m.studioUrl());
            }
        }
        return event;
    }

    /** SHA-256 hex of {@code artemis-studio|<ruleId>|<subject>}: stable, and inside PagerDuty's 255 limit. */
    static String dedupKey(AlertMessage m, AlertMessage.Line line) {
        String raw = "artemis-studio|" + m.ruleId() + "|" + line.subject();
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String severity(String severity) {
        return switch (severity == null ? "" : severity) {
            case "CRITICAL" -> "critical";
            case "WARNING" -> "warning";
            default -> "info";
        };
    }

    static String endpoint(String channelConfigJson, ObjectMapper mapper) {
        try {
            JsonNode url = mapper.readTree(channelConfigJson == null ? "{}" : channelConfigJson)
                    .get("url");
            return url == null || url.isNull() || url.asString().isBlank() ? DEFAULT_URL : url.asString();
        } catch (RuntimeException e) {
            return DEFAULT_URL;
        }
    }
}
