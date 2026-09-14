package io.github.sudoitir.artemisstudio.platform.broker;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.boot.http.client.JdkHttpClientBuilder;
import org.springframework.boot.ssl.NoSuchSslBundleException;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
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
 *
 * <p>The underlying JDK {@link HttpClient} is shared: one per TLS bundle, never one per
 * call. Each client owns a selector thread and an epoll descriptor that live until the
 * client is shut down, so building one per Jolokia call leaked threads and descriptors
 * until the container was OOM-killed. A client is shut down when its settings are
 * replaced, when its bundle's material is reloaded, and when the context closes.
 */
@Component
public class BrokerClientFactory implements DisposableBean {

    private static final String PLAIN = "";

    private final ObjectMapper mapper;
    private final SslBundles sslBundles;
    private final ClockOffsetRegistry clockOffsets;
    private final NodeCallHealth callHealth;
    private final NodeCallLimiter limiter;
    private volatile HttpClientSettings baseSettings;

    /** One transport per TLS bundle name ({@link #PLAIN} for none). Replaced wholesale on a timeout change. */
    private volatile Map<String, Transport> transports = new ConcurrentHashMap<>();

    /** Bundles whose reload handler is registered, so a reload evicts the client built from old material. */
    private final Set<String> watchedBundles = ConcurrentHashMap.newKeySet();

    /** Resolved broker MBean names, shared across every client this factory builds (keyed by Jolokia URL). */
    private final Map<String, String> brokerObjectNames = new ConcurrentHashMap<>();

    public BrokerClientFactory(
            ObjectMapper mapper,
            SslBundles sslBundles,
            BrokerProperties properties,
            ClockOffsetRegistry clockOffsets,
            NodeCallHealth callHealth,
            NodeCallLimiter limiter) {
        this.mapper = mapper;
        this.sslBundles = sslBundles;
        this.clockOffsets = clockOffsets;
        this.callHealth = callHealth;
        this.limiter = limiter;
        this.baseSettings = HttpClientSettings.defaults()
                .withConnectTimeout(properties.connectTimeout())
                .withReadTimeout(properties.readTimeout())
                // A Jolokia agent never redirects. The Artemis console does: a seed URL
                // pointing at /console bounces to the Hawtio login page, and following
                // that turns a wrong-path mistake into an unreadable "not a Jolokia
                // response". Surfacing the 3xx keeps the real diagnosis visible.
                .withRedirects(HttpRedirects.DONT_FOLLOW);
    }

    /**
     * Runtime override hook — {@code SettingsService} calls this when either broker
     * timeout changes. The shared clients are replaced, so the next Jolokia call uses the
     * new timeouts; the old clients are shut down gracefully, so a request already in
     * flight finishes on the old ones.
     */
    public void setTimeouts(Duration connectTimeout, Duration readTimeout) {
        this.baseSettings = baseSettings.withConnectTimeout(connectTimeout).withReadTimeout(readTimeout);
        Map<String, Transport> old = transports;
        transports = new ConcurrentHashMap<>();
        old.values().forEach(Transport::shutdown);
    }

    public JolokiaBrokerClient forNode(BrokerConnectionSettings settings, String jolokiaUrl) {
        RestClient.Builder builder = RestClient.builder()
                .requestFactory(transport(settings).factory())
                .messageConverters(converters -> applyJolokiaConverters(converters, mapper));
        if (settings.hasCredentials()) {
            builder.requestInterceptor((request, body, execution) -> {
                request.getHeaders().setBasicAuth(settings.username(), settings.password());
                return execution.execute(request, body);
            });
        }
        return new JolokiaBrokerClient(
                builder.build(), jolokiaUrl, mapper, brokerObjectNames, clockOffsets, callHealth, limiter);
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

    @Override
    public void destroy() {
        Map<String, Transport> all = transports;
        transports = new ConcurrentHashMap<>();
        all.values().forEach(Transport::shutdown);
    }

    private Transport transport(BrokerConnectionSettings settings) {
        String bundle = settings.hasTls() ? settings.tlsBundle() : PLAIN;
        return transports.computeIfAbsent(bundle, this::build);
    }

    /**
     * Mirrors Boot's {@code JdkClientHttpRequestFactoryBuilder}: the client takes every
     * setting but the read timeout, which the JDK applies per request and so lives on the
     * request factory. Built here rather than through that builder because the builder
     * hides the {@link HttpClient}, and a client that cannot be shut down is the leak.
     */
    private Transport build(String bundle) {
        HttpClientSettings s = baseSettings;
        if (!PLAIN.equals(bundle)) {
            // Hostname verification follows the bundle's own SslOptions; the
            // per-cluster verify_hostname flag is surfaced in the topology view
            // so an operator can see it, and is configured on the bundle itself.
            s = s.withSslBundle(resolveBundle(bundle));
            if (watchedBundles.add(bundle)) {
                sslBundles.addBundleUpdateHandler(bundle, reloaded -> evict(bundle));
            }
        }
        HttpClient client = new JdkHttpClientBuilder().build(s.withReadTimeout(null));
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        if (s.readTimeout() != null) {
            factory.setReadTimeout(s.readTimeout());
        }
        return new Transport(client, factory);
    }

    private void evict(String bundle) {
        Transport stale = transports.remove(bundle);
        if (stale != null) {
            stale.shutdown();
        }
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

    private record Transport(HttpClient client, JdkClientHttpRequestFactory factory) {
        void shutdown() {
            client.shutdown();
        }
    }
}
