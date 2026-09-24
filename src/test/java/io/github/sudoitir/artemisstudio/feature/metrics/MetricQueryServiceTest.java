package io.github.sudoitir.artemisstudio.feature.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.feature.metrics.web.MetricViews.MetricSeriesResponse;
import io.github.sudoitir.artemisstudio.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterDirectory;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterNode;
import io.github.sudoitir.artemisstudio.platform.scrape.MetricSampleReaper;
import io.github.sudoitir.artemisstudio.platform.scrape.MetricSamples;
import io.github.sudoitir.artemisstudio.platform.scrape.MetricSamples.Bucket;
import io.github.sudoitir.artemisstudio.platform.scrape.MetricSamples.NodeBucket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MetricQueryServiceTest {

    @Mock
    MetricSamples repository;

    @Mock
    MetricSampleReaper reaper;

    /** Permissive by default: an unstubbed void call is a no-op, i.e. access granted.
     * The guard's real behaviour is covered by {@code ClusterScopeAuthorizationTest}. */
    @Mock
    ClusterAccessGuard clusterAccess;

    @Mock
    ClusterDirectory directory;

    MetricQueryService service;

    private final UUID clusterId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(reaper.retentionDays()).thenReturn(7);
        service = new MetricQueryService(repository, reaper, clusterAccess, directory);
    }

    @Test
    void counterResetWithinABucketNeverProducesANegativeRate() {
        // messagesAdded goes 100 -> 150 -> 10 (a broker restart) within one bucket;
        // rateSeries itself is exercised by MetricSamples's own SQL, but the
        // clamp lives in that SQL's GREATEST(...,0) — here we assert the service
        // passes the repository's already-clamped value straight through, never
        // re-introducing a negative number of its own.
        Instant bucket = Instant.parse("2026-01-01T00:00:00Z");
        when(repository.rateSeries(eq(clusterId), eq("messagesAdded"), any(), any(), any(), any()))
                .thenReturn(List.of(new Bucket(bucket, 0.0, null)));

        MetricSeriesResponse response = service.query(
                clusterId,
                List.of("messagesAdded"),
                "CLUSTER",
                null,
                bucket,
                bucket.plusSeconds(60),
                Duration.ofSeconds(60));

        assertThat(response.series().get(0).points())
                .allSatisfy(p -> assertThat(p.value()).isNotNegative());
    }

    @Test
    void aFinerStepThanAllowedIsClampedAndMarkedTruncated() {
        when(repository.gaugeSeries(any(), anyString(), any(), any(), any(), any()))
                .thenReturn(List.of());

        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofDays(7));
        MetricSeriesResponse response =
                service.query(clusterId, List.of("messageCount"), "CLUSTER", null, from, to, Duration.ofSeconds(1));

        assertThat(response.truncated()).isTrue();
        assertThat(Duration.parse(response.step())).isGreaterThan(Duration.ofSeconds(1));
    }

    @Test
    void aRangeBeyondRetentionIsClampedAndMarkedTruncated() {
        when(repository.gaugeSeries(any(), anyString(), any(), any(), any(), any()))
                .thenReturn(List.of());

        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofDays(30));
        MetricSeriesResponse response =
                service.query(clusterId, List.of("messageCount"), "CLUSTER", null, from, to, null);

        assertThat(response.truncated()).isTrue();
        assertThat(response.from()).isAfter(from);
    }

    private static ClusterNode node(UUID id, String name, boolean active) {
        ClusterNode n = mock(ClusterNode.class);
        when(n.getId()).thenReturn(id);
        when(n.getName()).thenReturn(name);
        when(n.getActive()).thenReturn(active);
        return n;
    }

    @Test
    void splitByNodeNamesEachNodeAndListsAServingNodeWithNoSampleAsNotSampled() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID backup = UUID.randomUUID();
        // Built before the stubbing that returns them: Mockito cannot stub inside another stubbing.
        List<ClusterNode> nodes = List.of(
                node(a, "artemis-a", true), node(b, "artemis-b", true), node(backup, "artemis-a-backup", false));
        when(directory.nodes(clusterId)).thenReturn(nodes);
        Instant to = Instant.now();
        when(repository.rateSeries(any(), anyString(), any(), any(), any(), any()))
                .thenReturn(List.of(new Bucket(to.minusSeconds(60), 30.0, null)));
        when(repository.rateSeriesByNode(eq(clusterId), eq("messagesAdded"), eq("orders"), any(), any(), any()))
                .thenReturn(List.of(new NodeBucket(a, to.minusSeconds(60), 30.0, null)));

        MetricSeriesResponse response = service.query(
                clusterId, List.of("messagesAdded"), "QUEUE", "orders", to.minusSeconds(3600), to, null, "NODE");

        assertThat(response.splitBy()).isEqualTo("NODE");
        assertThat(response.series().get(0).points()).hasSize(1);
        assertThat(response.byNode())
                .extracting(n -> n.nodeName() + ":" + n.sampled())
                // The standby is neither serving nor sampled, so it is not a node of this split.
                .containsExactly("artemis-a:true", "artemis-b:false");
        assertThat(response.byNode().get(1).series().get(0).points()).isEmpty();
    }

    @Test
    void splitByNodeWidensTheStepSoEveryNodeTogetherStaysWithinTheBound() {
        List<ClusterNode> many = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            many.add(node(UUID.randomUUID(), "node-" + i, true));
        }
        when(directory.nodes(clusterId)).thenReturn(many);
        Instant to = Instant.now();

        MetricSeriesResponse response = service.query(
                clusterId,
                List.of("messageCount"),
                "QUEUE",
                "orders",
                to.minus(Duration.ofHours(24)),
                to,
                Duration.ofSeconds(60),
                "NODE");

        long buckets = Duration.ofHours(24).dividedBy(Duration.parse(response.step()));
        assertThat(buckets * many.size()).isLessThanOrEqualTo(2_000);
        assertThat(response.truncated()).isTrue();
    }

    @Test
    void aSplitIsRefusedForTheClusterScopeAndForAnyOtherDimension() {
        Instant to = Instant.now();
        assertThatThrownBy(() -> service.query(
                        clusterId, List.of("messageCount"), "CLUSTER", null, to.minusSeconds(60), to, null, "NODE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("needs one queue");
        assertThatThrownBy(() -> service.query(
                        clusterId,
                        List.of("messageCount"),
                        "QUEUE",
                        "orders",
                        to.minusSeconds(60),
                        to,
                        null,
                        "ADDRESS"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("splitBy must be NODE");
    }
}
