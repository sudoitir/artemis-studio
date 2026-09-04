package io.github.sudoitir.artemisstudio.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.github.sudoitir.artemisstudio.broker.BrokerCapabilities.CapabilityStatus;
import io.github.sudoitir.artemisstudio.broker.core.CoreEventClient;
import io.github.sudoitir.artemisstudio.broker.core.SubscriptionVerdict;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class CapabilityProbeTest {

    private static final String URL = "http://broker-1:8161/console/jolokia";

    private final JsonMapper mapper = new JsonMapper();
    private final CapabilityProbe probe = new CapabilityProbe();

    private record Fixture(JolokiaBrokerClient client, MockRestServiceServer server) {}

    /** Every request the probe sends must be read-only: search / read, or exec of listNetworkTopology only. */
    private RequestMatcher readOnly() {
        return request -> {
            String body = ((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString();
            JsonNode root = mapper.readTree(body);
            for (JsonNode entry :
                    root.isArray() ? root : mapper.createArrayNode().add(root)) {
                String type = entry.get("type").asText();
                assertThat(type).isIn("search", "read", "exec");
                if (type.equals("exec")) {
                    assertThat(entry.get("operation").asText()).startsWith("listNetworkTopology");
                }
            }
        };
    }

    private Fixture fixture(String... orderedFixtureNames) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        for (String name : orderedFixtureNames) {
            server.expect(requestTo(URL))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(readOnly())
                    .andRespond(withSuccess(body(name), MediaType.APPLICATION_JSON));
        }
        return new Fixture(new JolokiaBrokerClient(builder.build(), URL, mapper), server);
    }

    private static String body(String fixtureName) {
        try {
            return new String(
                    new ClassPathResource("jolokia/" + fixtureName).getContentAsByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void readAndWriteAvailableNotificationsUnknownMessageIoDegraded() {
        Fixture f = fixture(
                "search-broker.json",
                "capability-version-read.json",
                "topology.json",
                "acceptors.json",
                "acceptor-params-core.json",
                "addresses-with-notifications.json");

        BrokerCapabilities caps = probe.probe(f.client(), new SubscriptionVerdict.NotAttempted());

        assertThat(caps.managementRead().status()).isEqualTo(CapabilityStatus.AVAILABLE);
        assertThat(caps.managementWrite().status()).isEqualTo(CapabilityStatus.AVAILABLE);
        assertThat(caps.managementWrite().reason()).contains("jolokia-access.xml");
        // No scrape cycle has produced a subscription outcome yet.
        assertThat(caps.notifications().status()).isEqualTo(CapabilityStatus.UNKNOWN);
        assertThat(caps.notifications().reason()).contains("first scrape cycle has not completed");
        assertThat(caps.notifications().reason()).contains("CORE acceptor present");
        assertThat(caps.notifications().reason()).contains("activemq.notifications address present");
        assertThat(caps.messageIo().status()).isEqualTo(CapabilityStatus.AVAILABLE);
        assertThat(caps.messageIo().reason()).contains("truncates").contains("Phase 4");
        f.server().verify();
    }

    @Test
    void writeAndMessageIoUnavailableWhenExecIsRefused() {
        Fixture f = fixture(
                "search-broker.json",
                "capability-version-read.json",
                "exec-forbidden.json",
                "acceptors.json",
                "acceptor-params-core.json",
                "addresses-with-notifications.json");

        BrokerCapabilities caps = probe.probe(f.client(), new SubscriptionVerdict.NotAttempted());

        assertThat(caps.managementRead().status()).isEqualTo(CapabilityStatus.AVAILABLE);
        assertThat(caps.managementWrite().status()).isEqualTo(CapabilityStatus.UNAVAILABLE);
        assertThat(caps.managementWrite().reason()).contains("read-only policy");
        assertThat(caps.messageIo().status()).isEqualTo(CapabilityStatus.UNAVAILABLE);
        assertThat(caps.notifications().status()).isEqualTo(CapabilityStatus.UNKNOWN);
        f.server().verify();
    }

    @Test
    void notificationsReasonReportsMissingPreconditions() {
        Fixture f = fixture(
                "search-broker.json",
                "capability-version-read.json",
                "topology.json",
                "acceptors-empty.json",
                "addresses-without-notifications.json");

        BrokerCapabilities caps = probe.probe(f.client(), new SubscriptionVerdict.NotAttempted());

        assertThat(caps.notifications().status()).isEqualTo(CapabilityStatus.UNKNOWN);
        assertThat(caps.notifications().reason()).contains("CORE acceptor not found");
        assertThat(caps.notifications().reason()).contains("activemq.notifications address not found");
        f.server().verify();
    }

    @Test
    void everythingUnknownWhenManagementReadFails() {
        // search resolves the broker, but the attribute read comes back non-200.
        Fixture f = fixture("search-broker.json", "exec-forbidden.json");

        BrokerCapabilities caps = probe.probe(f.client(), new SubscriptionVerdict.NotAttempted());

        assertThat(caps.managementRead().status()).isEqualTo(CapabilityStatus.UNAVAILABLE);
        assertThat(caps.managementWrite().status()).isEqualTo(CapabilityStatus.UNKNOWN);
        assertThat(caps.notifications().status()).isEqualTo(CapabilityStatus.UNKNOWN);
        assertThat(caps.messageIo().status()).isEqualTo(CapabilityStatus.UNKNOWN);
        f.server().verify();
    }

    @Test
    void notificationsAvailableWhenSubscribed() {
        Fixture f = fixture(
                "search-broker.json",
                "capability-version-read.json",
                "topology.json",
                "acceptors.json",
                "acceptor-params-core.json",
                "addresses-with-notifications.json");

        BrokerCapabilities caps = probe.probe(f.client(), new SubscriptionVerdict.Connected(1, Instant.now()));

        assertThat(caps.notifications().status()).isEqualTo(CapabilityStatus.AVAILABLE);
        assertThat(caps.notifications().reason()).contains("Subscribed to activemq.notifications");
        assertThat(caps.notifications().brokerXmlSnippet()).contains("NotificationActiveMQServerPlugin");
        f.server().verify();
    }

    @Test
    void notificationsUnavailableWhenSubscriptionRefusedForPermission() {
        Fixture f = fixture(
                "search-broker.json",
                "capability-version-read.json",
                "topology.json",
                "acceptors.json",
                "acceptor-params-core.json",
                "addresses-with-notifications.json");

        BrokerCapabilities caps = probe.probe(
                f.client(), new SubscriptionVerdict.Failed(CoreEventClient.Kind.PERMISSION_DENIED, "AMQ229213"));

        assertThat(caps.notifications().status()).isEqualTo(CapabilityStatus.UNAVAILABLE);
        assertThat(caps.notifications().brokerXmlSnippet()).contains("consume").contains("createNonDurableQueue");
        f.server().verify();
    }

    @Test
    void notificationsUnavailableWhenNoCoreUrl() {
        Fixture f = fixture(
                "search-broker.json",
                "capability-version-read.json",
                "topology.json",
                "acceptors-empty.json",
                "addresses-without-notifications.json");

        BrokerCapabilities caps =
                probe.probe(f.client(), new SubscriptionVerdict.Failed(CoreEventClient.Kind.NO_CORE_URL, "no url"));

        assertThat(caps.notifications().status()).isEqualTo(CapabilityStatus.UNAVAILABLE);
        assertThat(caps.notifications().brokerXmlSnippet()).contains("acceptor");
        f.server().verify();
    }
}
