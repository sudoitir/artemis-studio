package io.github.sudoitir.artemisstudio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.github.sudoitir.artemisstudio.broker.BrokerConnectionException;
import io.github.sudoitir.artemisstudio.broker.BrokerConnections;
import io.github.sudoitir.artemisstudio.broker.BrokerListOps;
import io.github.sudoitir.artemisstudio.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.config.ArtemisStudioProperties;
import io.github.sudoitir.artemisstudio.config.ArtemisStudioProperties.RateLimit;
import io.github.sudoitir.artemisstudio.mapper.ResourceViewMapper;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeEntity;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeRepository;
import io.github.sudoitir.artemisstudio.scheduler.NodeCallLimiter;
import io.github.sudoitir.artemisstudio.web.dto.ResourceViews.ConsumerView;
import io.github.sudoitir.artemisstudio.web.dto.ResourceViews.PagedView;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PagedListServiceTest {

    private static final String URL_A = "http://a:8161/console/jolokia";
    private static final String URL_B = "http://b:8161/console/jolokia";

    private final JsonMapper mapper = new JsonMapper();

    @Mock
    BrokerNodeRepository nodes;

    @Mock
    BrokerConnections connections;

    PagedListService service;

    @BeforeEach
    void setUp() {
        NodeCallLimiter limiter =
                new NodeCallLimiter(new ArtemisStudioProperties(null, null, null, new RateLimit(50), null, null, null));
        service = new PagedListService(nodes, connections, new BrokerListOps(), new ResourceViewMapper(), limiter);
    }

    private JolokiaBrokerClient client(String url, String... fixtures) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        for (String fixture : fixtures) {
            server.expect(requestTo(url)).andRespond(body(fixture));
        }
        return new JolokiaBrokerClient(builder.build(), url, mapper);
    }

    private static ResponseCreator body(String fixture) {
        try {
            String json = new String(
                    new ClassPathResource("jolokia/" + fixture).getContentAsByteArray(), StandardCharsets.UTF_8);
            return withSuccess(json, MediaType.APPLICATION_JSON);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static BrokerNodeEntity node(UUID clusterId, String name, String url) {
        BrokerNodeEntity n = BrokerNodeEntity.fromSeed(
                clusterId, name, "PRIMARY", UUID.randomUUID().toString());
        n.attachManagementUrl(url);
        try {
            var id = BrokerNodeEntity.class.getDeclaredField("id");
            id.setAccessible(true);
            id.set(n, UUID.randomUUID());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return n;
    }

    @Test
    void oneNodeDownStillReturnsRowsFromTheOthersTaggedByNode() {
        UUID clusterId = UUID.randomUUID();
        BrokerNodeEntity a = node(clusterId, "node-a", URL_A);
        BrokerNodeEntity b = node(clusterId, "node-b", URL_B);
        when(nodes.findByClusterIdOrderByNameAsc(clusterId)).thenReturn(List.of(a, b));
        when(connections.forCluster(eq(clusterId), eq(URL_A)))
                .thenThrow(BrokerConnectionException.of(BrokerConnectionException.Kind.UNREACHABLE));
        when(connections.forCluster(eq(clusterId), eq(URL_B)))
                .thenReturn(client(URL_B, "search-broker.json", "list-consumers.json"));

        PagedView<ConsumerView> page = service.consumers(clusterId, ResourceQuery.of(null, 1, 50, null));

        assertThat(page.data()).hasSize(1);
        assertThat(page.data().get(0).nodeName()).isEqualTo("node-b");
        assertThat(page.data().get(0).queueName()).isEqualTo("SPIKE.A.q000");
    }

    @Test
    void everyNodeDownRethrowsTheClassifiedFailure() {
        UUID clusterId = UUID.randomUUID();
        BrokerNodeEntity a = node(clusterId, "node-a", URL_A);
        when(nodes.findByClusterIdOrderByNameAsc(clusterId)).thenReturn(List.of(a));
        when(connections.forCluster(eq(clusterId), eq(URL_A)))
                .thenThrow(BrokerConnectionException.of(BrokerConnectionException.Kind.UNAUTHORIZED));

        assertThatThrownBy(() -> service.consumers(clusterId, ResourceQuery.of(null, 1, 50, null)))
                .isInstanceOf(BrokerConnectionException.class)
                .extracting(e -> ((BrokerConnectionException) e).kind())
                .isEqualTo(BrokerConnectionException.Kind.UNAUTHORIZED);
    }

    @Test
    void noManageableNodeIsAnUnreachableProblem() {
        UUID clusterId = UUID.randomUUID();
        when(nodes.findByClusterIdOrderByNameAsc(clusterId)).thenReturn(List.of());

        assertThatThrownBy(() -> service.consumers(clusterId, ResourceQuery.of(null, 1, 50, null)))
                .isInstanceOf(BrokerConnectionException.class);
    }
}
