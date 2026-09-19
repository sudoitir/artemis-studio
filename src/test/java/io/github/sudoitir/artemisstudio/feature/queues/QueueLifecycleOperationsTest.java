package io.github.sudoitir.artemisstudio.feature.queues;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.platform.broker.BrokerConnectionException;
import io.github.sudoitir.artemisstudio.platform.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.platform.broker.JolokiaResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** What the queue delete preflight reads on a node (ADR-0084): absent is a fact, unread is not. */
class QueueLifecycleOperationsTest {

    private static final String BROKER = "org.apache.activemq.artemis:broker=\"b\"";
    private final ObjectMapper mapper = new ObjectMapper();
    private final QueueLifecycleOperations ops = new QueueLifecycleOperations(mapper);
    private final JolokiaBrokerClient client = mock(JolokiaBrokerClient.class);

    private JolokiaResponse ok(String attribute, Object value) {
        return new JolokiaResponse(200, mapper.valueToTree(Map.of(attribute, value)), null, null, null);
    }

    private static JolokiaResponse failed(String type, String error) {
        return new JolokiaResponse(type.contains("InstanceNotFound") ? 404 : 500, null, error, type, null);
    }

    private static final JolokiaResponse NOT_FOUND =
            failed("javax.management.InstanceNotFoundException", "javax.management.InstanceNotFoundException : x");

    private QueueLifecycleOperations.DeleteState read(JolokiaResponse names, JolokiaResponse consumers) {
        when(client.batch(anyList())).thenReturn(List.of(names, consumers));
        return ops.deleteState(client, BROKER, "orders.addr", "orders", "ANYCAST");
    }

    @Test
    void aQueueOnItsAddressIsPresentWithItsConsumers() {
        var state = read(ok("QueueNames", List.of("orders", "audit")), ok("ConsumerCount", 2));

        assertThat(state.present()).isTrue();
        assertThat(state.consumerCount()).isEqualTo(2L);
        assertThat(state.addressQueues()).containsExactly("orders", "audit");
    }

    @Test
    void anAddressThatIsNotThereMeansTheQueueIsAbsent() {
        var state = read(NOT_FOUND, NOT_FOUND);

        assertThat(state.present()).isFalse();
    }

    @Test
    void aQueueNamesReadThatFailsIsNotTakenAsAbsence() {
        assertThatThrownBy(() -> read(failed("java.lang.SecurityException", "denied"), ok("ConsumerCount", 0)))
                .isInstanceOf(BrokerConnectionException.class)
                .hasMessageContaining("QueueNames");
    }

    @Test
    void aConsumerCountReadThatFailsIsNotTakenAsZero() {
        assertThatThrownBy(() ->
                        read(ok("QueueNames", List.of("orders")), failed("java.lang.RuntimeException", "timeout")))
                .isInstanceOf(BrokerConnectionException.class)
                .hasMessageContaining("ConsumerCount");
    }
}
