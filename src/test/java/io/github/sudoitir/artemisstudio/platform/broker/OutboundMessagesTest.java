package io.github.sudoitir.artemisstudio.platform.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.platform.broker.OutboundMessages.Outbound;
import io.github.sudoitir.artemisstudio.platform.broker.OutboundMessages.Provenance;
import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.apache.activemq.artemis.api.core.ActiveMQBuffer;
import org.apache.activemq.artemis.api.core.Message;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.api.core.client.ClientMessage;
import org.apache.activemq.artemis.core.client.impl.ClientMessageImpl;
import org.apache.activemq.artemis.reader.MapMessageUtil;
import org.apache.activemq.artemis.reader.StreamMessageUtil;
import org.apache.activemq.artemis.reader.TextMessageUtil;
import org.apache.activemq.artemis.utils.DataConstants;
import org.apache.activemq.artemis.utils.collections.TypedProperties;
import org.junit.jupiter.api.Test;

/** The outbound build of a relayed message (transfer design D3), one body type at a time. */
class OutboundMessagesTest {

    private static final UUID RUN = UUID.randomUUID();
    private static final UUID CLUSTER = UUID.randomUUID();
    private static final Provenance COPY = new Provenance(RUN, CLUSTER, "node-1", "orders", false);

    @Test
    void textBodyIsCopiedVerbatim() throws Exception {
        ClientMessageImpl source =
                source(Message.TEXT_TYPE, b -> TextMessageUtil.writeBodyText(b, SimpleString.of("hello")));
        try (Outbound out = OutboundMessages.from(source, COPY)) {
            assertSameBody(source, out.message());
            assertThat(TextMessageUtil.readBodyText(out.message().getReadOnlyBodyBuffer())
                            .toString())
                    .isEqualTo("hello");
        }
    }

    @Test
    void bytesBodyIsCopiedVerbatim() throws Exception {
        ClientMessageImpl source = source(Message.BYTES_TYPE, b -> b.writeBytes(new byte[] {0, 1, 2, (byte) 255}));
        try (Outbound out = OutboundMessages.from(source, COPY)) {
            assertSameBody(source, out.message());
        }
    }

    @Test
    void mapBodyIsCopiedVerbatim() throws Exception {
        TypedProperties map = new TypedProperties();
        map.putIntProperty(SimpleString.of("qty"), 7);
        map.putSimpleStringProperty(SimpleString.of("sku"), SimpleString.of("A-1"));
        ClientMessageImpl source = source(Message.MAP_TYPE, b -> MapMessageUtil.writeBodyMap(b, map));
        try (Outbound out = OutboundMessages.from(source, COPY)) {
            assertSameBody(source, out.message());
            TypedProperties copied = MapMessageUtil.readBodyMap(out.message().getReadOnlyBodyBuffer());
            assertThat(copied.getIntProperty(SimpleString.of("qty"))).isEqualTo(7);
        }
    }

    @Test
    void streamBodyIsCopiedVerbatim() throws Exception {
        ClientMessageImpl source = source(Message.STREAM_TYPE, b -> {
            b.writeByte(DataConstants.INT);
            b.writeInt(42);
        });
        try (Outbound out = OutboundMessages.from(source, COPY)) {
            assertSameBody(source, out.message());
            assertThat(StreamMessageUtil.streamReadInteger(out.message().getReadOnlyBodyBuffer()))
                    .isEqualTo(42);
        }
    }

    @Test
    void objectBodyIsCopiedVerbatim() throws Exception {
        ByteArrayOutputStream serialized = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(serialized)) {
            oos.writeObject("a serialisable payload");
        }
        byte[] bytes = serialized.toByteArray();
        ClientMessageImpl source = source(Message.OBJECT_TYPE, b -> {
            b.writeInt(bytes.length);
            b.writeBytes(bytes);
        });
        try (Outbound out = OutboundMessages.from(source, COPY)) {
            assertSameBody(source, out.message());
        }
    }

    @Test
    void headersAndApplicationPropertiesTravelAndBrokerBookkeepingDoesNot() throws Exception {
        ClientMessageImpl source =
                new ClientMessageImpl(Message.TEXT_TYPE, true, 1_900_000_000_000L, 1_700_000_000_000L, (byte) 7, 256);
        TextMessageUtil.writeBodyText(source.getBodyBuffer(), SimpleString.of("x"));
        source.setMessageID(99);
        source.setUserID(new org.apache.activemq.artemis.utils.UUID(
                org.apache.activemq.artemis.utils.UUID.TYPE_TIME_BASED, new byte[16]));
        source.putStringProperty("app", "kept");
        source.putStringProperty(Message.HDR_GROUP_ID, SimpleString.of("group-1"));
        source.putStringProperty("JMSCorrelationID", "corr-1");
        source.putStringProperty(Message.HDR_LAST_VALUE_NAME, SimpleString.of("lvq-key"));
        for (SimpleString bookkeeping : OutboundMessages.DROPPED) {
            source.putStringProperty(bookkeeping, SimpleString.of("source-only"));
        }

        try (Outbound out = OutboundMessages.from(source, COPY)) {
            ClientMessage m = out.message();
            assertThat(m.isDurable()).isTrue();
            assertThat(m.getPriority()).isEqualTo((byte) 7);
            assertThat(m.getExpiration()).isEqualTo(1_900_000_000_000L);
            assertThat(m.getTimestamp()).isEqualTo(1_700_000_000_000L);
            assertThat(m.getUserID()).isEqualTo(source.getUserID());
            assertThat(m.getStringProperty("app")).isEqualTo("kept");
            assertThat(m.getStringProperty(Message.HDR_GROUP_ID)).isEqualTo("group-1");
            assertThat(m.getStringProperty("JMSCorrelationID")).isEqualTo("corr-1");
            assertThat(m.getStringProperty(Message.HDR_LAST_VALUE_NAME)).isEqualTo("lvq-key");

            Set<SimpleString> names = m.getPropertyNames();
            OutboundMessages.DROPPED.stream()
                    .filter(h -> !h.equals(Message.HDR_DUPLICATE_DETECTION_ID))
                    .forEach(h -> assertThat(names).doesNotContain(h));
            assertThat(m.getStringProperty(Message.HDR_DUPLICATE_DETECTION_ID)).isEqualTo("studio:" + RUN + ":99");
        }
    }

    @Test
    void provenanceNamesTheSourceAndAStagedCopyKeepsItsSourceQueueId() throws Exception {
        ClientMessageImpl source =
                source(Message.TEXT_TYPE, b -> TextMessageUtil.writeBodyText(b, SimpleString.of("x")));
        source.setMessageID(500);
        source.putLongProperty(Message.HDR_ORIG_MESSAGE_ID, 31L);

        try (Outbound copied = OutboundMessages.from(source, COPY)) {
            ClientMessage m = copied.message();
            assertThat(m.getStringProperty(OutboundMessages.RUN)).isEqualTo(RUN.toString());
            assertThat(m.getStringProperty(OutboundMessages.ORIG_CLUSTER)).isEqualTo(CLUSTER.toString());
            assertThat(m.getStringProperty(OutboundMessages.ORIG_NODE)).isEqualTo("node-1");
            assertThat(m.getStringProperty(OutboundMessages.ORIG_QUEUE)).isEqualTo("orders");
            assertThat(m.getLongProperty(OutboundMessages.ORIG_MESSAGE_ID)).isEqualTo(500L);
        }
        Provenance staged = new Provenance(RUN, CLUSTER, "node-1", "orders", true);
        try (Outbound moved = OutboundMessages.from(source, staged)) {
            assertThat(moved.message().getLongProperty(OutboundMessages.ORIG_MESSAGE_ID))
                    .isEqualTo(31L);
            // The duplicate id is the id relayed from, which is stable while the message waits in staging.
            assertThat(moved.message().getStringProperty(Message.HDR_DUPLICATE_DETECTION_ID))
                    .isEqualTo("studio:" + RUN + ":500");
        }
    }

    @Test
    void aLargeMessageIsSpooledToAnOwnerOnlyFileAndDeletedOnClose() throws Exception {
        byte[] payload = new byte[5 * 1024 * 1024];
        Arrays.fill(payload, (byte) 'z');
        ClientMessage source = mock(ClientMessage.class);
        when(source.isLargeMessage()).thenReturn(true);
        when(source.getType()).thenReturn(Message.BYTES_TYPE);
        when(source.isDurable()).thenReturn(true);
        when(source.getMessageID()).thenReturn(7L);
        when(source.getPropertyNames()).thenReturn(Set.of(Message.HDR_LARGE_BODY_SIZE));
        when(source.getLongProperty(Message.HDR_LARGE_BODY_SIZE)).thenReturn((long) payload.length);
        doAnswer(inv -> {
                    ((OutputStream) inv.getArgument(0)).write(payload);
                    return null;
                })
                .when(source)
                .saveToOutputStream(any());

        Outbound out = OutboundMessages.from(source, COPY);
        assertThat(out.spool()).exists().startsWith(OutboundMessages.spoolRoot().resolve(RUN.toString()));
        assertThat(Files.size(out.spool())).isEqualTo(payload.length);
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(out.spool())))
                .isEqualTo("rw-------");
        assertThat(out.message().getBodyInputStream().readAllBytes()).isEqualTo(payload);
        assertThat(out.message().getPropertyNames()).doesNotContain(Message.HDR_LARGE_BODY_SIZE);

        out.close();
        assertThat(out.spool()).doesNotExist();
    }

    private static ClientMessageImpl source(byte type, Consumer<ActiveMQBuffer> body) {
        ClientMessageImpl message = new ClientMessageImpl(type, true, 0, System.currentTimeMillis(), (byte) 4, 256);
        body.accept(message.getBodyBuffer());
        message.setMessageID(1);
        return message;
    }

    private static void assertSameBody(ClientMessage source, ClientMessage copy) {
        assertThat(copy.getType()).isEqualTo(source.getType());
        assertThat(bytes(copy.getReadOnlyBodyBuffer())).isEqualTo(bytes(source.getReadOnlyBodyBuffer()));
    }

    private static byte[] bytes(ActiveMQBuffer buffer) {
        byte[] b = new byte[buffer.readableBytes()];
        buffer.readBytes(b);
        return b;
    }
}
