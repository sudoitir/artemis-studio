package io.github.sudoitir.artemisstudio.broker.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.broker.BrokerTime;
import io.github.sudoitir.artemisstudio.broker.CoreMessageTransport;
import io.github.sudoitir.artemisstudio.broker.MessageBrowser.BodyEncoding;
import io.github.sudoitir.artemisstudio.broker.MessageBrowser.BrowsePage;
import io.github.sudoitir.artemisstudio.broker.MessageBrowser.BrowsedMessage;
import io.github.sudoitir.artemisstudio.broker.MessageTransport.BrowseResult;
import io.github.sudoitir.artemisstudio.broker.MessageTransport.Channel;
import io.github.sudoitir.artemisstudio.broker.MessageTransport.TransportTarget;
import io.github.sudoitir.artemisstudio.broker.rr.QueueTargetResolver;
import io.github.sudoitir.artemisstudio.broker.rr.ReplyAddressResolver;
import io.github.sudoitir.artemisstudio.domain.rr.Observation;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeEntity;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeRepository;
import io.github.sudoitir.artemisstudio.persist.RrExpectationEntity;
import io.github.sudoitir.artemisstudio.persist.RrExpectationRepository;
import io.github.sudoitir.artemisstudio.service.ClockOffsetService;
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

    /**
     * An expectation as it comes back from the repository — with an id.
     *
     * <p>The sampler keys its per-expectation rate limit and health on that id, and
     * it only ever iterates saved rows, so an unsaved entity is not a state it can
     * encounter.
     */
    private static RrExpectationEntity saved(RrExpectationEntity e) {
        try {
            java.lang.reflect.Field id = RrExpectationEntity.class.getDeclaredField("id");
            id.setAccessible(true);
            id.set(e, java.util.UUID.randomUUID());
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        return e;
    }

    private record Fixture(RrSampler sampler, List<Observation> seen, RrSamplerHealth health) {}

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

        ClockOffsetService clocks = mock(ClockOffsetService.class);
        when(clocks.brokerTime()).thenReturn(BrokerTime.identity());

        // The queue name and routing type come from the last scrape now, rather than
        // being assumed equal to the address and ANYCAST.
        QueueTargetResolver queueTargets = mock(QueueTargetResolver.class);
        when(queueTargets.resolve(eq(CLUSTER), any(), any()))
                .thenAnswer(inv ->
                        java.util.Optional.of(new QueueTargetResolver.QueueTarget(inv.getArgument(2), "ANYCAST")));

        RrSamplerHealth health = new RrSamplerHealth(java.time.Clock.systemUTC());

        return new Fixture(
                new RrSampler(expectations, nodeRepo, transport, provider, resolver, clocks, queueTargets, health),
                seen,
                health);
    }

    @Test
    void oneFailingNodeDoesNotCostTheOthersTheirTick() {
        RrExpectationEntity expectation =
                saved(new RrExpectationEntity(CLUSTER, "rr.request", List.of(), null, null, 10, false));

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
        RrExpectationEntity expectation = saved(new RrExpectationEntity(
                CLUSTER, "rr.request", List.of("rr.reply.a", "rr.reply.b"), null, null, 10, false));

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
                saved(new RrExpectationEntity(CLUSTER, "rr.request", List.of(), null, null, 10, false));

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

    @Test
    void aClusterWithNoCoreEndpointSaysSoInsteadOfDoingNothingQuietly() {
        // The loop over serving nodes was simply empty: nothing sampled, nothing
        // logged, and an operator staring at an empty Flows tab with no way to tell
        // that from "no traffic".
        RrExpectationEntity expectation =
                saved(new RrExpectationEntity(CLUSTER, "rr.request", List.of(), null, null, 10, false));
        BrokerNodeEntity noCore = node("broker-1", null);
        Fixture f = samplerOver(List.of(noCore), expectation, mock(CoreMessageTransport.class));

        f.sampler().tick();

        RrSamplerHealth.ExpectationHealth health =
                f.health().forExpectation(expectation.getId()).orElseThrow();
        assertThat(health.nodesSampled()).isZero();
        assertThat(health.skipped())
                .singleElement()
                .satisfies(o -> assertThat(o.reason()).isEqualTo(RrSampler.NO_CORE_ENDPOINT));
    }

    @Test
    void samplePerMinIsHonouredRatherThanStoredAndIgnored() {
        // It was written to the database, rendered in the table, and never read: the
        // UI was offering a control that did nothing.
        RrExpectationEntity expectation =
                saved(new RrExpectationEntity(CLUSTER, "rr.request", List.of(), null, null, 1, false));
        CoreMessageTransport transport = mock(CoreMessageTransport.class);
        when(transport.browse(any(TransportTarget.class), anyInt(), anyInt(), any()))
                .thenReturn(new BrowseResult(new BrowsePage(List.of(), 0L), Channel.CORE));
        Fixture f = samplerOver(List.of(node("broker-1", "tcp://10.0.0.1:61616")), expectation, transport);

        // One sample per minute: the second tick, moments later, must not browse.
        f.sampler().tick();
        f.sampler().tick();

        verify(transport, times(1)).browse(any(TransportTarget.class), anyInt(), anyInt(), any());
    }
}
