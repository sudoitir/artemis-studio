package io.github.sudoitir.rahdar.platform.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class BrokerListOpsTest {

    @Test
    void readsAThousandNamesInChunksOfTwoHundredAndKeepsEveryAnswer() {
        JolokiaBrokerClient client = mock(JolokiaBrokerClient.class);
        var mapper = JsonMapper.builder().build();
        when(client.batch(anyList()))
                .thenAnswer(call -> ((List<?>) call.getArgument(0))
                        .stream()
                                .map(_ -> new JolokiaResponse(
                                        200, mapper.createObjectNode().put("Name", "x"), null, null, null))
                                .toList());
        List<String> names =
                IntStream.range(0, 450).mapToObj(i -> "bridge-" + i).toList();

        List<String> rows =
                BrokerListOps.readAll(client, names, node -> node.get("Name").asText());

        assertThat(rows).hasSize(450);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<JolokiaRequest>> sent = ArgumentCaptor.forClass(List.class);
        verify(client, times(3)).batch(sent.capture());
        assertThat(sent.getAllValues()).extracting(List::size).containsExactly(200, 200, 50);
    }

    @Test
    void readsNothingForNoNames() {
        JolokiaBrokerClient client = mock(JolokiaBrokerClient.class);

        assertThat(BrokerListOps.readAll(client, List.of(), node -> node)).isEmpty();
        verify(client, times(0)).batch(anyList());
    }
}
