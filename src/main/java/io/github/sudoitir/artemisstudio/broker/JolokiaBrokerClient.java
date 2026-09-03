package io.github.sudoitir.artemisstudio.broker;

import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLException;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
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

    private final RestClient restClient;
    private final String jolokiaUrl;
    private final ObjectMapper mapper;

    private volatile String cachedBrokerObjectName;

    public JolokiaBrokerClient(RestClient restClient, String jolokiaUrl, ObjectMapper mapper) {
        this.restClient = restClient;
        this.jolokiaUrl = jolokiaUrl;
        this.mapper = mapper;
    }

    public String jolokiaUrl() {
        return jolokiaUrl;
    }

    /** Send a bulk request; the returned list is positionally aligned with {@code requests}. */
    public List<JolokiaResponse> batch(List<JolokiaRequest> requests) {
        JolokiaResponse[] body = post(requests, JolokiaResponse[].class);
        return body == null ? List.of() : List.of(body);
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
                    .body(payload)
                    .retrieve()
                    .body(responseType);
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden e) {
            throw new BrokerConnectionException(
                    BrokerConnectionException.Kind.UNAUTHORIZED,
                    BrokerConnectionException.Kind.UNAUTHORIZED.defaultMessage(),
                    e);
        } catch (HttpClientErrorException.NotFound e) {
            throw new BrokerConnectionException(
                    BrokerConnectionException.Kind.WRONG_PATH,
                    BrokerConnectionException.Kind.WRONG_PATH.defaultMessage(),
                    e);
        } catch (HttpStatusCodeException e) {
            throw new BrokerConnectionException(
                    BrokerConnectionException.Kind.BAD_RESPONSE,
                    "The broker responded " + e.getStatusCode().value() + ".",
                    e);
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
                    BrokerConnectionException.Kind.BAD_RESPONSE.defaultMessage(),
                    e);
        }
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
