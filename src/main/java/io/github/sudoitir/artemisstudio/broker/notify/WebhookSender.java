package io.github.sudoitir.artemisstudio.broker.notify;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
 * Delivers to a generic webhook receiver, signed per the Standard Webhooks spec
 * (ADR-0036): {@code webhook-id}/{@code webhook-timestamp}/{@code webhook-signature}
 * headers over the raw payload body. {@code webhook-id} is the delivery's own row
 * id, giving the receiver a free idempotency key across retries of the same row.
 */
@Component
public class WebhookSender implements NotificationSender {

    private final RestClient restClient;
    private final ObjectMapper mapper;

    public WebhookSender(@Qualifier("notificationRestClient") RestClient restClient, ObjectMapper mapper) {
        this.restClient = restClient;
        this.mapper = mapper;
    }

    @Override
    public String kind() {
        return "WEBHOOK";
    }

    @Override
    public Result send(long deliveryId, String channelConfigJson, String signingSecret, String payloadJson) {
        String url = configUrl(channelConfigJson);
        if (url == null) {
            return Result.permanent("Webhook channel has no configured url");
        }
        String id = "alert-delivery-" + deliveryId;
        long timestamp = Instant.now().getEpochSecond();
        String signature = WebhookSigner.sign(id, timestamp, payloadJson, signingSecret);
        try {
            restClient
                    .post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("webhook-id", id)
                    .header("webhook-timestamp", Long.toString(timestamp))
                    .header("webhook-signature", signature)
                    .body(payloadJson)
                    .retrieve()
                    .toBodilessEntity();
            return Result.ok();
        } catch (HttpClientErrorException.TooManyRequests e) {
            return Result.retryable("Webhook receiver rate limited", retryAfter(e.getResponseHeaders()));
        } catch (HttpStatusCodeException e) {
            return Result.retryable("Webhook responded " + e.getStatusCode());
        } catch (RestClientException e) {
            return Result.retryable(e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private String configUrl(String channelConfigJson) {
        try {
            Map<String, Object> config = mapper.readValue(channelConfigJson, Map.class);
            Object url = config.get("url");
            return url == null ? null : url.toString();
        } catch (RuntimeException e) {
            return null;
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
