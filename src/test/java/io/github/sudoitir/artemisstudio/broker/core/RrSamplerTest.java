package io.github.sudoitir.artemisstudio.broker.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.broker.CoreMessageTransport;
import io.github.sudoitir.artemisstudio.broker.MessageBrowser.BodyEncoding;
import io.github.sudoitir.artemisstudio.broker.MessageBrowser.BrowsePage;
import io.github.sudoitir.artemisstudio.broker.MessageBrowser.BrowsedMessage;
import io.github.sudoitir.artemisstudio.broker.MessageTransport.BrowseResult;
import io.github.sudoitir.artemisstudio.broker.MessageTransport.Channel;
import io.github.sudoitir.artemisstudio.broker.MessageTransport.TransportTarget;
import io.github.sudoitir.artemisstudio.broker.rr.ReplyAddressResolver;
import io.github.sudoitir.artemisstudio.domain.rr.Observation;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeEntity;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeRepository;
import io.github.sudoitir.artemisstudio.persist.RrExpectationEntity;
import io.github.sudoitir.artemisstudio.persist.RrExpectationRepository;
import io.github.sudoitir.artemisstudio.service.RrObservationSink;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@link RrSampler} node fan-out (design.md D7). The sampler used to browse only the
 * first active node, so in a multi-primary cluster the traffic on the others was
 * never read.
 */
class RrSamplerTest {

    private static final UUID CLUSTER = UUID.randomUUID();

    private static BrokerNodeEntity node(String name, String coreUrl) {
        BrokerNodeEntity n = BrokerNodeEntity.fromSeed(CLUSTER, name, "PRIMARY", name);
        n.applyManualCoreUrl(coreUrl);
        n.applyHaState(true, "STARTED", "PRIMARY", true, 1L, "2.44.0", name, Instant.now());
        return n;
    }

    private static BrowsedMessage message(long id, String correlationId) {
        return new BrowsedMessage(
                id,
                0,
                true,
                4,
                0L,
                0L,
                0L,
                null,
                correlationId,
                null,
                null,
                null,
                BodyEncoding.TEXT,
                null,
                false,
                null,
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of());
    }

    private record Fixture(RrSampler sampler, List<Observation> seen) {}

    @SuppressWarnings("unchecked")
    private static Fixture samplerOver(
            List<BrokerNodeEntity> nodes, RrExpectationEntity expectation, CoreMessageTransport transport) {
        RrExpectationRepository expectations = mock(RrExpectationRepository.class);
        when(expectations.findByEnabledTrue()).thenReturn(List.of(expectation));

        BrokerNodeRepository nodeRepo = mock(BrokerNodeRepository.class);
        when(nodeRepo.findByClusterIdOrderByNameAsc(CLUSTER)).thenReturn(nodes);

        List<Observation> seen = new ArrayList<>();
        ObjectProvider<RrObservationSink> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn((RrObservationSink) seen::add);

        ReplyAddressResolver resolver = mock(ReplyAddressResolver.class);
        when(resolver.resolve(eq(CLUSTER), any()))
                .thenReturn(new ReplyAddressResolver.Resolution(expectation.getReplyAddresses(), false, false));

        return new Fixture(new RrSampler(expectations, nodeRepo, transport, provider, resolver), seen);
    }

    @Test
    void oneFailingNodeDoesNotCostTheOthersTheirTick() {
        RrExpectationEntity expectation =
                new RrExpectationEntity(CLUSTER, "rr.request", List.of(), null, null, 10, false);

        CoreMessageTransport transport = mock(CoreMessageTransport.class);
        when(transport.browse(any(TransportTarget.class), anyInt(), anyInt(), any()))
                .thenAnswer(invocation -> {
                    TransportTarget target = invocation.getArgument(0);
                    if ("core://broken:61616".equals(target.coreUrl())) {
                        throw new IllegalStateException("node unreachable");
                    }
                    return new BrowseResult(new BrowsePage(List.of(message(1L, "corr-1")), 1L), Channel.CORE);
                });

        Fixture f = samplerOver(
                List.of(node("a-broken", "core://broken:61616"), node("b-healthy", "core://healthy:61616")),
                expectation,
                transport);

        f.sampler().tick();

        // The failing node is first by name, which is exactly the ordering that used
        // to decide the single node the sampler would use.
        assertThat(f.seen()).hasSize(1);
        assertThat(f.seen().getFirst()).isInstanceOf(Observation.RequestSeen.class);
    }

    @Test
    void everyServingNodeIsBrowsedForEveryResolvedReplyAddress() {
        RrExpectationEntity expectation = new RrExpectationEntity(
                CLUSTER, "rr.request", List.of("rr.reply.a", "rr.reply.b"), null, null, 10, false);

        List<String> browsed = new ArrayList<>();
        CoreMessageTransport transport = mock(CoreMessageTransport.class);
        when(transport.browse(any(TransportTarget.class), anyInt(), anyInt(), any()))
                .thenAnswer(invocation -> {
                    TransportTarget target = invocation.getArgument(0);
                    browsed.add(target.coreUrl() + " " + target.address());
                    return new BrowseResult(new BrowsePage(List.of(), 0L), Channel.CORE);
                });

        Fixture f = samplerOver(
                List.of(node("n1", "core://one:61616"), node("n2", "core://two:61616")), expectation, transport);

        f.sampler().tick();

        assertThat(browsed)
                .containsExactlyInAnyOrder(
                        "core://one:61616 rr.request",
                        "core://one:61616 rr.reply.a",
                        "core://one:61616 rr.reply.b",
                        "core://two:61616 rr.request",
                        "core://two:61616 rr.reply.a",
                        "core://two:61616 rr.reply.b");
    }

    @Test
    void aNodeWithNoCoreUrlOrALastErrorIsNotBrowsed() {
        RrExpectationEntity expectation =
                new RrExpectationEntity(CLUSTER, "rr.request", List.of(), null, null, 10, false);

        BrokerNodeEntity errored = node("n-errored", "core://errored:61616");
        errored.recordError(Instant.now(), "last scrape failed");
        BrokerNodeEntity noCore = BrokerNodeEntity.fromSeed(CLUSTER, "n-no-core", "PRIMARY", "n-no-core");
        noCore.applyHaState(true, "STARTED", "PRIMARY", true, 1L, "2.44.0", "n-no-core", Instant.now());

        List<String> browsed = new ArrayList<>();
        CoreMessageTransport transport = mock(CoreMessageTransport.class);
        when(transport.browse(any(TransportTarget.class), anyInt(), anyInt(), any()))
                .thenAnswer(invocation -> {
                    browsed.add(((TransportTarget) invocation.getArgument(0)).coreUrl());
                    return new BrowseResult(new BrowsePage(List.of(), 0L), Channel.CORE);
                });

        Fixture f = samplerOver(List.of(errored, noCore, node("n-ok", "core://ok:61616")), expectation, transport);

        f.sampler().tick();

        assertThat(browsed).containsExactly("core://ok:61616");
    }
}
