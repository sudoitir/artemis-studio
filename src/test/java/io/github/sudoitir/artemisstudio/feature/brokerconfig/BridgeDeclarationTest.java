package io.github.sudoitir.artemisstudio.feature.brokerconfig;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.feature.brokerconfig.BrokerConfigDocument.BridgeDecl;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.BrokerConfigDocument.DivertDecl;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.BrokerConfigDocument.TransformerDecl;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.BrokerXmlCodec.ParseResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A bridge is declared configuration like every other section (ADR-0091): it survives
 * the XML round trip in full, its two connector sources are exclusive, and its
 * credential is a reference that no path renders.
 */
class BridgeDeclarationTest {

    static BridgeDecl bridge() {
        return new BridgeDecl(
                "orders-out",
                "orders.out",
                "orders.in",
                "region = 'eu' AND size < 10 AND name <> \"x\" AND flag = 'a&b'",
                new TransformerDecl("com.example.Rewrite", Map.of("mode", "strict", "depth", "2")),
                List.of("remote-a", "remote-b"),
                null,
                true,
                true,
                2000L,
                1.5,
                30000L,
                -1,
                -1,
                10485760,
                1048576,
                102400,
                30000L,
                60000L,
                "PASS",
                2,
                "studio-bridge",
                "orders-out-credential");
    }

    private static BrokerConfigDocument doc(BridgeDecl... bridges) {
        return new BrokerConfigDocument(1, List.of(), List.of(), List.of(), List.of(), List.of(bridges));
    }

    @Test
    void aBridgeRoundTripsThroughTheCodecUnchanged() {
        BridgeDecl declared = bridge();

        ParseResult back = BrokerXmlCodec.parse(BrokerXmlCodec.write(doc(declared)));

        assertThat(back.errors()).isEmpty();
        assertThat(back.document().bridges()).hasSize(1);
        BridgeDecl parsed = back.document().bridges().getFirst();
        // The credential is not part of the round trip, by design: it is a reference
        // into the vault and the export carries only a placeholder naming it.
        assertThat(parsed)
                .isEqualTo(new BridgeDecl(
                        declared.name(),
                        declared.queueName(),
                        declared.forwardingAddress(),
                        declared.filter(),
                        declared.transformer(),
                        declared.staticConnectors(),
                        declared.discoveryGroupName(),
                        declared.ha(),
                        declared.useDuplicateDetection(),
                        declared.retryInterval(),
                        declared.retryIntervalMultiplier(),
                        declared.maxRetryInterval(),
                        declared.initialConnectAttempts(),
                        declared.reconnectAttempts(),
                        declared.confirmationWindowSize(),
                        declared.producerWindowSize(),
                        declared.minLargeMessageSize(),
                        declared.checkPeriod(),
                        declared.connectionTtl(),
                        declared.routingType(),
                        declared.concurrency(),
                        declared.clientId(),
                        null));
    }

    @Test
    void aFilterCarryingMarkupSurvivesTheRoundTrip() {
        String xml = BrokerXmlCodec.write(doc(bridge()));

        assertThat(xml).doesNotContain("size < 10");
        assertThat(BrokerXmlCodec.parse(xml).document().bridges().getFirst().filter())
                .isEqualTo("region = 'eu' AND size < 10 AND name <> \"x\" AND flag = 'a&b'");
    }

    @Test
    void aTransformersPropertiesSurviveTheRoundTripOnADivertToo() {
        DivertDecl divert = new DivertDecl(
                "audit", "orders.in", "audit.in", null, false, null, "com.example.Tag", Map.of("k", "v", "k2", "v2"));
        BrokerConfigDocument d =
                new BrokerConfigDocument(1, List.of(), List.of(), List.of(), List.of(divert), List.of());

        ParseResult back = BrokerXmlCodec.parse(BrokerXmlCodec.write(d));

        assertThat(back.document().diverts().getFirst().transformerProperties())
                .containsExactlyInAnyOrderEntriesOf(Map.of("k", "v", "k2", "v2"));
    }

    @Test
    void namingBothConnectorSourcesIsRefusedByField() {
        BridgeDecl both = new BridgeDecl(
                "b",
                "q",
                "a",
                null,
                null,
                List.of("remote-a"),
                "dg",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);

        assertThat(BrokerConfigValidator.validate(doc(both)))
                .extracting(Violation::path)
                .containsExactly("bridges[0].staticConnectors");
    }

    @Test
    void namingNeitherConnectorSourceIsRefusedByField() {
        BridgeDecl neither = new BridgeDecl(
                "b", "q", "a", null, null, List.of(), null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);

        List<Violation> violations = BrokerConfigValidator.validate(doc(neither));

        assertThat(violations).extracting(Violation::path).containsExactly("bridges[0].staticConnectors");
        assertThat(violations.getFirst().message()).contains("discovery group");
    }

    @Test
    void aBridgeWithoutAQueueOrAnAddressIsRefusedByField() {
        BridgeDecl bare = new BridgeDecl(
                "has space",
                null,
                null,
                null,
                null,
                List.of("remote-a"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);

        assertThat(BrokerConfigValidator.validate(doc(bare)))
                .extracting(Violation::path)
                .containsExactly("bridges[0].name", "bridges[0].queueName", "bridges[0].forwardingAddress");
    }
}
