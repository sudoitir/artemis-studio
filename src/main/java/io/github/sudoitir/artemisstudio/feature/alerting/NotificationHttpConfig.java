package io.github.sudoitir.artemisstudio.feature.alerting;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * A {@code RestClient} for outbound notification delivery only (ADR-0036) — built
 * with the same {@link HttpClientSettings} recipe as
 * {@code BrokerClientFactory}, but its own bean: it must never share the
 * per-node Jolokia rate limiter or a broker's TLS bundle with a call to a
 * Slack/webhook endpoint.
 */
@Configuration
public class NotificationHttpConfig {

    @Bean
    public RestClient notificationRestClient(AlertingProperties properties) {
        HttpClientSettings settings = HttpClientSettings.defaults()
                .withConnectTimeout(properties.connectTimeout())
                .withReadTimeout(properties.connectTimeout());
        return RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .build();
    }
}
