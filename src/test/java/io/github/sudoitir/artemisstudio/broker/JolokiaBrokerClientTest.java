package io.github.sudoitir.artemisstudio.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class JolokiaBrokerClientTest {

    private static final String URL = "http://broker-1:8161/console/jolokia";
    private final ObjectMapper mapper = new JsonMapper();

    private record Fixture(JolokiaBrokerClient client, MockRestServiceServer server) {}

    private Fixture fixture(String fixtureName, HttpStatus status) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(status)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body(fixtureName)));
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
    void resolvesBrokerObjectNameFromSearch() {
        Fixture f = fixture("search-broker.json", HttpStatus.OK);

        assertThat(f.client().resolveBrokerObjectName()).isEqualTo("org.apache.activemq.artemis:broker=\"primary\"");
        f.server().verify();
    }

    @Test
    void emptySearchMeansNotArtemis() {
        Fixture f = fixture("search-broker-empty.json", HttpStatus.OK);

        assertThatThrownBy(f.client()::resolveBrokerObjectName)
                .isInstanceOf(BrokerConnectionException.class)
                .extracting(e -> ((BrokerConnectionException) e).kind())
                .isEqualTo(BrokerConnectionException.Kind.NOT_ARTEMIS);
    }

    @Test
    void doubleEncodedTopologyValueIsParsedASecondTime() {
        Fixture f = fixture("topology.json", HttpStatus.OK);

        JolokiaResponse response = f.client().single(JolokiaRequest.exec("x", "listNetworkTopology()"));
        assertThat(response.value().isTextual()).isTrue();

        JsonNode parsed = response.valueParsed(mapper);
        assertThat(parsed.isArray()).isTrue();
        assertThat(parsed.get(0).get("nodeID").asText()).isEqualTo("f7734597-a768-11f1-aa4c-ceae3fa2df1d");
        assertThat(parsed.get(0).get("live").asText()).isEqualTo("artemis-primary:61616");
        assertThat(parsed.get(0).get("backup").asText()).isEqualTo("artemis-backup:61616");
    }

    @Test
    void postFailoverTopologyHasNoBackupKey() {
        Fixture f = fixture("topology-after-failover.json", HttpStatus.OK);

        JsonNode parsed = f.client()
                .single(JolokiaRequest.exec("x", "listNetworkTopology()"))
                .valueParsed(mapper);

        assertThat(parsed).hasSize(1);
        assertThat(parsed.get(0).has("backup")).isFalse();
        assertThat(parsed.get(0).get("live").asText()).isEqualTo("artemis-backup:61616");
    }

    @Test
    void batchIsolatesAFailedEntryFromSuccessfulOnes() {
        Fixture f = fixture("batch-mixed-status.json", HttpStatus.OK);

        List<JolokiaResponse> responses = f.client()
                .batch(List.of(
                        JolokiaRequest.read("b", "Active", "ReplicaSync", "NodeID"),
                        JolokiaRequest.exec("b", "listNetworkTopology()"),
                        JolokiaRequest.exec("b", "listQueues(java.lang.String,int,int)", "", 1, 50),
                        JolokiaRequest.read("bad", "X")));

        assertThat(responses).hasSize(4);
        assertThat(responses.get(0).ok()).isTrue();
        assertThat(responses.get(0).value().get("Active").asBoolean()).isTrue();
        assertThat(responses.get(1).ok()).isTrue();
        assertThat(responses.get(2).ok()).isTrue();

        JolokiaResponse failed = responses.get(3);
        assertThat(failed.ok()).isFalse();
        assertThat(failed.status()).isEqualTo(404);
        assertThat(failed.errorType()).isEqualTo("javax.management.InstanceNotFoundException");
    }

    @Test
    void multiAttributeReadReturnsAnObjectValue() {
        Fixture f = fixture("ha-read-primary.json", HttpStatus.OK);

        JsonNode value = f.client()
                .single(JolokiaRequest.read("b", "Active", "Started", "Backup", "ReplicaSync", "NodeID"))
                .value();

        assertThat(value.get("Active").asBoolean()).isTrue();
        assertThat(value.get("Started").asBoolean()).isTrue();
        assertThat(value.get("Backup").asBoolean()).isFalse();
        assertThat(value.get("NodeID").asText()).isEqualTo("f7734597-a768-11f1-aa4c-ceae3fa2df1d");
    }

    @Test
    void unauthorizedIsClassified() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        JolokiaBrokerClient client = new JolokiaBrokerClient(builder.build(), URL, mapper);

        assertThatThrownBy(() -> client.search(BrokerMBeans.BROKER_SEARCH_PATTERN))
                .isInstanceOf(BrokerConnectionException.class)
                .extracting(e -> ((BrokerConnectionException) e).kind())
                .isEqualTo(BrokerConnectionException.Kind.UNAUTHORIZED);
    }

    @Test
    void notFoundIsClassifiedAsWrongPath() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));
        JolokiaBrokerClient client = new JolokiaBrokerClient(builder.build(), URL, mapper);

        assertThatThrownBy(() -> client.search(BrokerMBeans.BROKER_SEARCH_PATTERN))
                .isInstanceOf(BrokerConnectionException.class)
                .extracting(e -> ((BrokerConnectionException) e).kind())
                .isEqualTo(BrokerConnectionException.Kind.WRONG_PATH);
    }

    @Test
    void execOnBrokerParsedResolvesThenReparses() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL)).andRespond(withSuccess(body("search-broker.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL)).andRespond(withSuccess(body("topology.json"), MediaType.APPLICATION_JSON));
        JolokiaBrokerClient client = new JolokiaBrokerClient(builder.build(), URL, mapper);

        JsonNode parsed = client.execOnBrokerParsed("listNetworkTopology()");

        assertThat(parsed.get(0).get("nodeID").asText()).isEqualTo("f7734597-a768-11f1-aa4c-ceae3fa2df1d");
        server.verify();
    }
}
