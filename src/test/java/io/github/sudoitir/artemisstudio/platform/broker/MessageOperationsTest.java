package io.github.sudoitir.artemisstudio.platform.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * An operation by ids that stops part-way has already changed the queue, so it must say how
 * much — reporting it as a plain failure tells an operator nothing happened. And it is sent in
 * batches, never one request per id.
 */
class MessageOperationsTest {

    private static final String QUEUE = "org.apache.activemq.artemis:broker=\"primary\",component=addresses";

    private final ObjectMapper mapper = new ObjectMapper();
    private final JolokiaBrokerClient client = mock(JolokiaBrokerClient.class);
    private final MessageOperations operations = new MessageOperations();

    @Test
    void idsAreSentInBatchesOfFiftyNotOneRequestEach() {
        when(client.batch(anyList())).thenAnswer(invocation -> {
            List<?> requests = invocation.getArgument(0);
            return requests.stream().map(r -> acted()).toList();
        });

        MessageOperations.BulkResult result = operations.deleteByIds(client, QUEUE, ids(1, 120));

        assertThat(result.affected()).isEqualTo(120);
        assertThat(result.partial()).isFalse();
        verify(client, times(3)).batch(anyList());
    }

    @Test
    void aRefusedIdMidBatchIsReportedAsPartialAndLaterBatchesAreNotSent() {
        when(client.batch(anyList())).thenAnswer(invocation -> {
            List<?> requests = invocation.getArgument(0);
            return java.util.stream.IntStream.range(0, requests.size())
                    .mapToObj(i -> i == 2 ? refused("AMQ229067: no such message") : acted())
                    .toList();
        });

        MessageOperations.BulkResult result = operations.deleteByIds(client, QUEUE, ids(1, 60));

        assertThat(result.partial()).isTrue();
        // Every result of the first batch is counted, because the broker ran all of them.
        assertThat(result.affected()).isEqualTo(49);
        assertThat(result.notDone()).hasSize(11).startsWith(3L).contains(51L, 60L);
        assertThat(result.error()).contains("AMQ229067");
        verify(client, times(1)).batch(anyList());
    }

    @Test
    void aLaterBatchThatCannotBeSentLeavesItsIdsNotDone() {
        when(client.batch(anyList()))
                .thenAnswer(invocation -> ((List<?>) invocation.getArgument(0))
                        .stream().map(r -> acted()).toList())
                .thenThrow(
                        new BrokerConnectionException(BrokerConnectionException.Kind.UNREACHABLE, "connection reset"));

        MessageOperations.BulkResult result = operations.moveByIds(client, QUEUE, ids(1, 60), "DLQ");

        assertThat(result.affected()).isEqualTo(50);
        assertThat(result.notDone()).containsExactlyElementsOf(ids(51, 60));
        assertThat(result.error()).contains("connection reset");
    }

    @Test
    void aFailureThatActedOnNothingIsAPlainFailure() {
        when(client.batch(anyList()))
                .thenThrow(new BrokerConnectionException(BrokerConnectionException.Kind.UNREACHABLE, "refused"));

        assertThatThrownBy(() -> operations.moveByIds(client, QUEUE, List.of(1L, 2L), "DLQ"))
                .isInstanceOf(BrokerConnectionException.class);
    }

    @Test
    void aMessageTheBrokerDidNotFindIsNotCountedButIsNotAFailure() {
        when(client.batch(anyList())).thenReturn(List.of(acted(), notFound()));

        MessageOperations.BulkResult result = operations.expireByIds(client, QUEUE, List.of(1L, 2L));

        assertThat(result.partial()).isFalse();
        assertThat(result.affected()).isEqualTo(1);
        assertThat(result.notDone()).isEmpty();
    }

    private static List<Long> ids(long from, long to) {
        return LongStream.rangeClosed(from, to).boxed().toList();
    }

    private JolokiaResponse acted() {
        return new JolokiaResponse(200, mapper.valueToTree(true), null, null, null);
    }

    private JolokiaResponse notFound() {
        return new JolokiaResponse(200, mapper.valueToTree(false), null, null, null);
    }

    private static JolokiaResponse refused(String error) {
        return new JolokiaResponse(500, null, error, "java.lang.IllegalArgumentException", null);
    }
}
