package io.github.sudoitir.artemisstudio.platform.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * An operation by ids that stops part-way has already changed the queue, so it must say how
 * much — reporting it as a plain failure tells an operator nothing happened.
 */
class MessageOperationsTest {

    private static final String QUEUE = "org.apache.activemq.artemis:broker=\"primary\",component=addresses";

    private final ObjectMapper mapper = new ObjectMapper();
    private final JolokiaBrokerClient client = mock(JolokiaBrokerClient.class);
    private final MessageOperations operations = new MessageOperations();

    @Test
    void aFailurePartWayIsReportedAsPartialWithWhatWasNotDone() {
        when(client.single(any(JolokiaRequest.class)))
                .thenReturn(acted())
                .thenReturn(acted())
                .thenThrow(
                        new BrokerConnectionException(BrokerConnectionException.Kind.UNREACHABLE, "connection reset"));

        MessageOperations.BulkResult result = operations.deleteByIds(client, QUEUE, List.of(1L, 2L, 3L, 4L));

        assertThat(result.partial()).isTrue();
        assertThat(result.affected()).isEqualTo(2);
        assertThat(result.notAttempted()).containsExactly(3L, 4L);
        assertThat(result.error()).contains("connection reset");
    }

    @Test
    void aFailureOnTheFirstIdIsAPlainFailure() {
        when(client.single(any(JolokiaRequest.class)))
                .thenThrow(new BrokerConnectionException(BrokerConnectionException.Kind.UNREACHABLE, "refused"));

        assertThatThrownBy(() -> operations.moveByIds(client, QUEUE, List.of(1L, 2L), "DLQ"))
                .isInstanceOf(BrokerConnectionException.class);
    }

    @Test
    void aCompleteRunIsNotPartial() {
        when(client.single(any(JolokiaRequest.class))).thenReturn(acted()).thenReturn(notFound());

        MessageOperations.BulkResult result = operations.expireByIds(client, QUEUE, List.of(1L, 2L));

        assertThat(result.partial()).isFalse();
        assertThat(result.affected()).isEqualTo(1);
        assertThat(result.notAttempted()).isEmpty();
    }

    private JolokiaResponse acted() {
        return new JolokiaResponse(200, mapper.valueToTree(true), null, null, null);
    }

    private JolokiaResponse notFound() {
        return new JolokiaResponse(200, mapper.valueToTree(false), null, null, null);
    }
}
