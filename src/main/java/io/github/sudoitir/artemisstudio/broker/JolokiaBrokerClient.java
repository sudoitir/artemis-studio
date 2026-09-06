package io.github.sudoitir.artemisstudio.broker;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.SSLException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.UnknownContentTypeException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A Jolokia client bound to one broker node's management URL.
 *
 * <p>Built by {@link BrokerClientFactory}. All calls go out as a single HTTP
 * POST to the node's Jolokia base URL — {@link #batch} sends a JSON array so one
 * scrape costs one request per node (ADR-0002 non-negotiable #1). Failures are
 * translated to a classified {@link BrokerConnectionException}.
 */
public class JolokiaBrokerClient {

    /** Hawtio names its own refusal reason here; Jolokia itself never sets it. */
    private static final String HAWTIO_FORBIDDEN_REASON = "Hawtio-Forbidden-Reason";

    private final RestClient restClient;
    private final String jolokiaUrl;
    private final ObjectMapper mapper;

    /**
     * Resolved broker MBean names keyed by Jolokia URL, shared across every client
     * instance the factory builds. Clients are rebuilt per call, so without this
     * every scrape tick would pay an extra {@code search} POST just to re-learn a
     * name that never changes. Populated once, then a tick is one POST.
     */
    private final Map<String, String> sharedBrokerObjectNames;

    private volatile String cachedBrokerObjectName;

    public JolokiaBrokerClient(RestClient restClient, String jolokiaUrl, ObjectMapper mapper) {
        this(restClient, jolokiaUrl, mapper, new ConcurrentHashMap<>());
    }

    public JolokiaBrokerClient(
            RestClient restClient,
            String jolokiaUrl,
            ObjectMapper mapper,
            Map<String, String> sharedBrokerObjectNames) {
        this.restClient = restClient;
        this.jolokiaUrl = jolokiaUrl;
        this.mapper = mapper;
        this.sharedBrokerObjectNames = sharedBrokerObjectNames;
        this.cachedBrokerObjectName = sharedBrokerObjectNames.get(jolokiaUrl);
    }

    public String jolokiaUrl() {
        return jolokiaUrl;
    }

    /** Send a bulk request; the returned list is positionally aligned with {@code requests}. */
    public List<JolokiaResponse> batch(List<JolokiaRequest> requests) {
        JolokiaResponse[] body = post(requests, JolokiaResponse[].class);
        return body == null ? List.of() : List.of(body);
    }

    /**
     * The double-decoded value of one entry from a {@link #batch} response. Use
     * for {@code listQueues} / {@code listNetworkTopology} entries, whose
     * {@code value} is a JSON-encoded string (Phase 0). Throws if the entry
     * itself failed.
     */
    public JsonNode parsed(JolokiaResponse entry) {
        if (!entry.ok()) {
            throw new BrokerConnectionException(
                    BrokerConnectionException.Kind.BAD_RESPONSE,
                    "Batch entry failed: " + (entry.error() != null ? entry.error() : "status " + entry.status()));
        }
        return entry.valueParsed(mapper);
    }

    /** Send a single request. */
    public JolokiaResponse single(JolokiaRequest request) {
        JolokiaResponse body = post(request, JolokiaResponse.class);
        if (body == null) {
            throw BrokerConnectionException.of(BrokerConnectionException.Kind.BAD_RESPONSE);
        }
        return body;
    }

    /** MBean names matching a JMX pattern. */
    public List<String> search(String pattern) {
        JolokiaResponse response = single(JolokiaRequest.search(pattern));
        if (!response.ok() || response.value() == null || !response.value().isArray()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        response.value().forEach(n -> names.add(n.asText()));
        return names;
    }

    /**
     * The broker's top-level MBean object name, resolved once via a Jolokia
     * {@code search} and cached.
     *
     * @throws BrokerConnectionException {@code NOT_ARTEMIS} if the agent answers
     *     but exposes no Artemis broker MBean.
     */
    public String resolveBrokerObjectName() {
        String cached = cachedBrokerObjectName;
        if (cached != null) {
            return cached;
        }
        List<String> matches = search(BrokerMBeans.BROKER_SEARCH_PATTERN);
        if (matches.isEmpty()) {
            throw BrokerConnectionException.of(BrokerConnectionException.Kind.NOT_ARTEMIS);
        }
        cachedBrokerObjectName = matches.get(0);
        sharedBrokerObjectNames.put(jolokiaUrl, cachedBrokerObjectName);
        return cachedBrokerObjectName;
    }

    /** Read attributes of the resolved broker MBean in one request. */
    public JolokiaResponse readBrokerAttributes(String... attributes) {
        return single(JolokiaRequest.read(resolveBrokerObjectName(), attributes));
    }

    /** Invoke an operation on the resolved broker MBean. */
    public JolokiaResponse execOnBroker(String operation, Object... arguments) {
        return single(JolokiaRequest.exec(resolveBrokerObjectName(), operation, arguments));
    }

    /** A broker-MBean operation whose {@code value} is a JSON string; returns the re-parsed node. */
    public JsonNode execOnBrokerParsed(String operation, Object... arguments) {
        JolokiaResponse response = execOnBroker(operation, arguments);
        if (!response.ok()) {
            throw new BrokerConnectionException(
                    BrokerConnectionException.Kind.BAD_RESPONSE,
                    "Operation " + operation + " failed: "
                            + (response.error() != null ? response.error() : "status " + response.status()));
        }
        return response.valueParsed(mapper);
    }

    private <T> T post(Object payload, Class<T> responseType) {
        try {
            return restClient
                    .post()
                    .uri(jolokiaUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    // 3xx is not an error status, so the default handler would let the
                    // redirect body through and it would fail to convert as "not a
                    // Jolokia response". Name it for what it is instead.
                    .onStatus(HttpStatusCode::is3xxRedirection, (request, response) -> {
                        throw redirected(response.getHeaders().getFirst(HttpHeaders.LOCATION));
                    })
                    .body(responseType);
        } catch (HttpStatusCodeException e) {
            throw classify(e);
        } catch (ResourceAccessException e) {
            if (hasCause(e, SSLException.class)) {
                throw new BrokerConnectionException(
                        BrokerConnectionException.Kind.TLS_FAILED,
                        BrokerConnectionException.Kind.TLS_FAILED.defaultMessage(),
                        e);
            }
            throw new BrokerConnectionException(
                    BrokerConnectionException.Kind.UNREACHABLE,
                    BrokerConnectionException.Kind.UNREACHABLE.defaultMessage(),
                    e);
        } catch (RestClientException e) {
            throw new BrokerConnectionException(
                    BrokerConnectionException.Kind.BAD_RESPONSE,
                    BrokerConnectionException.Kind.BAD_RESPONSE.defaultMessage() + notJsonHint(e),
                    e);
        }
    }

    /**
     * Classify an HTTP error status. Matching on the status value rather than on
     * {@code HttpClientErrorException} subclasses is deliberate: a console that
     * answers 403 with a zero-length body and no {@code Content-Type} does not
     * always arrive as the {@code Forbidden} subclass, and being misfiled as
     * {@code BAD_RESPONSE} makes an ordinary credential failure unreadable.
     */
    private static BrokerConnectionException classify(HttpStatusCodeException e) {
        int status = e.getStatusCode().value();
        if (status == 401 || status == 403) {
            return new BrokerConnectionException(
                    BrokerConnectionException.Kind.UNAUTHORIZED,
                    BrokerConnectionException.Kind.UNAUTHORIZED.defaultMessage() + refusalHint(e),
                    e);
        }
        if (status == 404) {
            return new BrokerConnectionException(
                    BrokerConnectionException.Kind.WRONG_PATH,
                    BrokerConnectionException.Kind.WRONG_PATH.defaultMessage(),
                    e);
        }
        return new BrokerConnectionException(
                BrokerConnectionException.Kind.BAD_RESPONSE, "The broker responded " + status + ".", e);
    }

    /** A Jolokia agent answers in place; only the console UI bounces the caller to a login page. */
    private static BrokerConnectionException redirected(String location) {
        return new BrokerConnectionException(
                BrokerConnectionException.Kind.WRONG_PATH,
                "The broker redirected the request"
                        + (location != null && !location.isBlank() ? " to " + location : "")
                        + " instead of answering it. That is the console UI, not the Jolokia agent"
                        + " \u2014 the URL should end in /console/jolokia.");
    }

    /**
     * Turn the broker's own refusal headers into something an operator can act on.
     * Artemis fronts Jolokia with Hawtio, which answers an unauthenticated request
     * with a bare 403 and a {@code Hawtio-Forbidden-Reason} header and no body at
     * all \u2014 without echoing that header the UI can only say "rejected".
     */
    private static String refusalHint(HttpStatusCodeException e) {
        StringBuilder hint = new StringBuilder();
        String hawtio = header(e, HAWTIO_FORBIDDEN_REASON);
        if (hawtio != null) {
            hint.append(" The console's Hawtio filter refused it (")
                    .append(HAWTIO_FORBIDDEN_REASON)
                    .append(": ")
                    .append(hawtio)
                    .append(")");
            hint.append(
                    "NONE".equalsIgnoreCase(hawtio)
                            ? " \u2014 that reason means no authenticated session, so the username or password is"
                                    + " missing or wrong, or the account holds no role allowed on the console."
                            : ".");
        }
        String challenge = header(e, HttpHeaders.WWW_AUTHENTICATE);
        if (challenge != null) {
            hint.append(" The broker asked for: ").append(challenge).append(".");
        }
        return hint.toString();
    }

    /** A 2xx that would not convert is nearly always the console's HTML login page. */
    private static String notJsonHint(RestClientException e) {
        if (e instanceof UnknownContentTypeException unknown) {
            MediaType type = unknown.getContentType();
            if (type != null && MediaType.TEXT_HTML.isCompatibleWith(type)) {
                return " It answered with an HTML page, which is the console UI rather than the"
                        + " Jolokia agent \u2014 check that the URL ends in /console/jolokia.";
            }
            if (type != null) {
                return " The response content type was " + type + ", not JSON.";
            }
        }
        return "";
    }

    private static String header(HttpStatusCodeException e, String name) {
        HttpHeaders headers = e.getResponseHeaders();
        if (headers == null) {
            return null;
        }
        String value = headers.getFirst(name);
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private static boolean hasCause(Throwable t, Class<? extends Throwable> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) {
                return true;
            }
        }
        return false;
    }
}
