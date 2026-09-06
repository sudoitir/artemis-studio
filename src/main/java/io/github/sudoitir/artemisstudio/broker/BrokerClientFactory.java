package io.github.sudoitir.artemisstudio.broker;

import io.github.sudoitir.artemisstudio.config.ArtemisStudioProperties;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.boot.ssl.NoSuchSslBundleException;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.converter.AbstractJacksonHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

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
    private final ClockOffsetRegistry clockOffsets;
    private volatile HttpClientSettings baseSettings;

    /** Resolved broker MBean names, shared across every client this factory builds (keyed by Jolokia URL). */
    private final Map<String, String> brokerObjectNames = new ConcurrentHashMap<>();

    public BrokerClientFactory(
            ObjectMapper mapper,
            SslBundles sslBundles,
            ArtemisStudioProperties properties,
            ClockOffsetRegistry clockOffsets) {
        this.mapper = mapper;
        this.sslBundles = sslBundles;
        this.clockOffsets = clockOffsets;
        this.baseSettings = HttpClientSettings.defaults()
                .withConnectTimeout(properties.broker().connectTimeout())
                .withReadTimeout(properties.broker().readTimeout())
                // A Jolokia agent never redirects. The Artemis console does: a seed URL
                // pointing at /console bounces to the Hawtio login page, and following
                // that turns a wrong-path mistake into an unreadable "not a Jolokia
                // response". Surfacing the 3xx keeps the real diagnosis visible.
                .withRedirects(HttpRedirects.DONT_FOLLOW);
    }

    /**
     * Runtime override hook — {@code SettingsService} calls this when either broker
     * timeout changes. {@code BrokerConnections} builds a client per call rather than
     * caching one, so the next Jolokia call already uses the new timeouts; only a
     * request already in flight keeps the old ones.
     */
    public void setTimeouts(java.time.Duration connectTimeout, java.time.Duration readTimeout) {
        this.baseSettings =
                HttpClientSettings.defaults().withConnectTimeout(connectTimeout).withReadTimeout(readTimeout);
    }

    public JolokiaBrokerClient forNode(BrokerConnectionSettings settings, String jolokiaUrl) {
        RestClient.Builder builder = RestClient.builder()
                .requestFactory(requestFactory(settings))
                .messageConverters(converters -> applyJolokiaConverters(converters, mapper));
        if (settings.hasCredentials()) {
            builder.requestInterceptor((request, body, execution) -> {
                request.getHeaders().setBasicAuth(settings.username(), settings.password());
                return execution.execute(request, body);
            });
        }
        return new JolokiaBrokerClient(builder.build(), jolokiaUrl, mapper, brokerObjectNames, clockOffsets);
    }

    /**
     * Jolokia does not always label its own JSON as JSON. The agent bundled with
     * Artemis 2.39 answers a perfectly valid Jolokia response with
     * {@code Content-Type: text/plain;charset=utf-8}; 2.44 sends
     * {@code application/json}. The stock converter claims only the JSON types, so
     * against the older broker every response failed to convert and surfaced as
     * "the broker answered, but not with a Jolokia response" — a real cluster
     * reported unreachable for a header. Claim {@code text/plain} too, first in
     * the list so it wins over the String converter.
     */
    static void applyJolokiaConverters(List<HttpMessageConverter<?>> converters, ObjectMapper mapper) {
        JacksonJsonHttpMessageConverter converter = mapper instanceof JsonMapper jsonMapper
                ? new JacksonJsonHttpMessageConverter(jsonMapper)
                : new JacksonJsonHttpMessageConverter();
        converter.setSupportedMediaTypes(
                List.of(MediaType.APPLICATION_JSON, MediaType.valueOf("application/*+json"), MediaType.TEXT_PLAIN));
        converters.removeIf(c -> c instanceof AbstractJacksonHttpMessageConverter);
        converters.add(0, converter);
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
