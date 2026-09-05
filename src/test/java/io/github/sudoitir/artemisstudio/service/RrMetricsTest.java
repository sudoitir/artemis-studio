package io.github.sudoitir.artemisstudio.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.domain.rr.Observation;
import io.github.sudoitir.artemisstudio.persist.ClusterEntity;
import io.github.sudoitir.artemisstudio.persist.ClusterRepository;
import io.github.sudoitir.artemisstudio.support.AdminAuthenticationExtension;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import io.github.sudoitir.artemisstudio.web.dto.RrViews.AddressStatsView;
import io.github.sudoitir.artemisstudio.web.dto.RrViews.StatsResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** {@link RrMetrics}: percentiles and coverage are never reported separately, and coverage starts unknown. */
@org.junit.jupiter.api.extension.ExtendWith(AdminAuthenticationExtension.class)
class RrMetricsTest extends PostgresIntegrationTest {

    @Autowired
    RrMetrics metrics;

    @Autowired
    RrCorrelator correlator;

    @Autowired
    ClusterRepository clusters;

    private UUID clusterId;

    @AfterEach
    void cleanUp() {
        if (clusterId != null) {
            clusters.deleteById(clusterId);
        }
    }

    private UUID cluster() {
        clusterId = clusters.save(new ClusterEntity("rr-metrics-" + UUID.randomUUID(), null, null))
                .getId();
        return clusterId;
    }

    @Test
    void coverageIsUnknownBeforeTheFirstBaselineReading() {
        UUID clusterId = cluster();
        Instant t0 = Instant.now();
        correlator.accept(new Observation.RequestSeen(
                clusterId, null, t0, "rr.request", "m1", "corr-1", null, 0L, null, Map.of()));
        correlator.accept(
                new Observation.ReplySeen(clusterId, null, t0.plusSeconds(1), null, "m2", "corr-1", null, Map.of()));

        StatsResponse stats = metrics.stats(clusterId, Duration.ofMinutes(15));
        AddressStatsView address = stats.addresses().stream()
                .filter(a -> a.address().equals("rr.request"))
                .findFirst()
                .orElseThrow();

        assertThat(address.completed()).isEqualTo(1);
        assertThat(address.sampled()).isTrue();
        assertThat(address.coverageRatio()).isNull();
        assertThat(address.p50Ms()).isNotNull();
    }

    @Test
    void inFlightAndOldestInFlightAreReported() {
        UUID clusterId = cluster();
        Instant t0 = Instant.now().minusSeconds(10);
        correlator.accept(new Observation.RequestSeen(
                clusterId, null, t0, "rr.pending", "m1", "corr-1", null, 0L, null, Map.of()));

        StatsResponse stats = metrics.stats(clusterId, Duration.ofMinutes(15));
        AddressStatsView address = stats.addresses().stream()
                .filter(a -> a.address().equals("rr.pending"))
                .findFirst()
                .orElseThrow();

        assertThat(address.inFlight()).isEqualTo(1);
        assertThat(address.oldestInFlightMs()).isGreaterThanOrEqualTo(9_000L);
    }
}
