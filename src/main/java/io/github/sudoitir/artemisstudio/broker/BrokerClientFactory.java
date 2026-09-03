package io.github.sudoitir.artemisstudio.broker;

import io.github.sudoitir.artemisstudio.config.ArtemisStudioProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.ssl.NoSuchSslBundleException;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Builds a {@link JolokiaBrokerClient} for a given broker node.
 *
 * <p>Per ADR-0010 the transport is a blocking {@link RestClient} on virtual
 * threads, with the connect / read timeouts from {@code artemis-studio.broker.*}.
 * TLS material comes from a named Spring SSL bundle (ADR-0009); a missing bundle
 * surfaces as a {@code TLS_FAILED} connection error rather than a silent
 * downgrade.
 */
@Component
public class BrokerClientFactory {

    private final ObjectMapper mapper;
    private final SslBundles sslBundles;
    private final HttpClientSettings baseSettings;

    public BrokerClientFactory(ObjectMapper mapper, SslBundles sslBundles, ArtemisStudioProperties properties) {
        this.mapper = mapper;
        this.sslBundles = sslBundles;
        this.baseSettings = HttpClientSettings.defaults()
                .withConnectTimeout(properties.broker().connectTimeout())
                .withReadTimeout(properties.broker().readTimeout());
    }

    public JolokiaBrokerClient forNode(BrokerConnectionSettings settings, String jolokiaUrl) {
        RestClient.Builder builder = RestClient.builder().requestFactory(requestFactory(settings));
        if (settings.hasCredentials()) {
            builder.requestInterceptor((request, body, execution) -> {
                request.getHeaders().setBasicAuth(settings.username(), settings.password());
                return execution.execute(request, body);
            });
        }
        return new JolokiaBrokerClient(builder.build(), jolokiaUrl, mapper);
    }

    private ClientHttpRequestFactory requestFactory(BrokerConnectionSettings settings) {
        HttpClientSettings s = baseSettings;
        if (settings.hasTls()) {
            // Hostname verification follows the bundle's own SslOptions; the
            // per-cluster verify_hostname flag is surfaced in the topology view
            // so an operator can see it, and is configured on the bundle itself.
            s = s.withSslBundle(resolveBundle(settings.tlsBundle()));
        }
        return ClientHttpRequestFactoryBuilder.detect().build(s);
    }

    private SslBundle resolveBundle(String name) {
        try {
            return sslBundles.getBundle(name);
        } catch (NoSuchSslBundleException e) {
            throw new BrokerConnectionException(
                    BrokerConnectionException.Kind.TLS_FAILED,
                    "TLS is configured for this cluster but SSL bundle '" + name
                            + "' is not defined. Add it under spring.ssl.bundle.",
                    e);
        }
    }
}
