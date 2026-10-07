package io.github.sudoitir.artemisstudio.feature.brokerconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.feature.brokerconfig.ConfigReader.NodeConfig;
import io.github.sudoitir.artemisstudio.platform.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.platform.broker.JolokiaRequest;
import io.github.sudoitir.artemisstudio.platform.broker.JolokiaResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The address-setting match set a comparison reads: capped, with {@code #} always in. */
class ConfigReaderTest {

    private final JsonMapper mapper = new JsonMapper();
    private final ConfigReader reader = new ConfigReader(mapper);

    @Test
    void theDefaultMatchComesFirstAndBrokerPlumbingIsLeftOut() {
        List<String> matches = ConfigReader.matchesFor(List.of("orders", "activemq.notifications", "$sys.x", " "));

        assertThat(matches).containsExactly("#", "orders");
    }

    @Test
    void aReadIsOneBatchedRequestAndTheCapKeepsTheDefaultMatch() {
        List<String> matches = new ArrayList<>(
                IntStream.range(0, 40).mapToObj(i -> "addr-" + i).toList());
        matches.add("#");
        JolokiaBrokerClient client = mock(JolokiaBrokerClient.class);
        when(client.resolveBrokerObjectName()).thenReturn("m");
        List<List<JolokiaRequest>> batches = new ArrayList<>();
        when(client.batch(anyList())).thenAnswer(call -> {
            List<JolokiaRequest> requests = call.getArgument(0);
            batches.add(requests);
            return requests.stream().map(r -> mock(JolokiaResponse.class)).toList();
        });
        when(client.parsed(any())).thenAnswer(call -> mapper.createObjectNode());

        NodeConfig config = reader.read(client, matches);

        assertThat(batches).hasSize(1);
        assertThat(config.matchesCompared()).isEqualTo(ConfigReader.MATCH_CAP);
        assertThat(config.matchesAvailable()).isEqualTo(41);
        assertThat(config.addressSettings()).hasSize(ConfigReader.MATCH_CAP);
        assertThat(config.addressSettings().get(0).get("match").asString()).isEqualTo("#");
    }
}
