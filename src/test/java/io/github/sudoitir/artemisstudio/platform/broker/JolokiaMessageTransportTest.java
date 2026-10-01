package io.github.sudoitir.artemisstudio.platform.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.platform.broker.MessageTransport.SendSpec;
import io.github.sudoitir.artemisstudio.platform.broker.MessageTransport.TransportTarget;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class JolokiaMessageTransportTest {

    @Test
    void headersAreSentUnderTheBrokersOwnNamesAsStringsAndTheBrokerAccountSends() {
        BrokerConnections connections = mock(BrokerConnections.class);
        JolokiaBrokerClient client = mock(JolokiaBrokerClient.class);
        MessageOperations ops = mock(MessageOperations.class);
        when(connections.forCluster(any(), any())).thenReturn(client);
        when(connections.settingsFor(any())).thenReturn(BrokerConnectionSettings.basicAuth(null, "bob", "pw"));
        when(client.resolveBrokerObjectName()).thenReturn("org.apache.activemq.artemis:broker=\"b\"");

        new JolokiaMessageTransport(connections, mock(MessageBrowser.class), ops)
                .send(
                        new TransportTarget(UUID.randomUUID(), UUID.randomUUID(), "q", "q", "ANYCAST", "u", null),
                        new SendSpec(
                                3,
                                true,
                                "hi",
                                false,
                                Map.of("correlationId", "c-1", "type", "order", "groupId", "g"),
                                Map.of("orderId", "A-1", "n", 7, "ok", true)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> sent = ArgumentCaptor.forClass(Map.class);
        verify(ops)
                .send(eq(client), anyString(), sent.capture(), anyInt(), eq("hi"), anyBoolean(), eq("bob"), eq("pw"));
        assertThat(sent.getValue())
                .containsOnly(
                        Map.entry("JMSCorrelationID", "c-1"),
                        Map.entry("JMSType", "order"),
                        Map.entry("_AMQ_GROUP_ID", "g"),
                        Map.entry("orderId", "A-1"),
                        Map.entry("n", "7"),
                        Map.entry("ok", "true"));
    }
}
