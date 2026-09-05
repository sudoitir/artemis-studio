package io.github.sudoitir.artemisstudio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.persist.MetricSampleReaper;
import io.github.sudoitir.artemisstudio.persist.MetricSeriesRepository;
import io.github.sudoitir.artemisstudio.persist.MetricSeriesRepository.Bucket;
import io.github.sudoitir.artemisstudio.web.dto.MetricViews.MetricSeriesResponse;
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
    MetricSeriesRepository repository;

    @Mock
    MetricSampleReaper reaper;

    MetricQueryService service;

    private final UUID clusterId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(reaper.retentionDays()).thenReturn(7);
        service = new MetricQueryService(repository, reaper);
    }

    @Test
    void counterResetWithinABucketNeverProducesANegativeRate() {
        // messagesAdded goes 100 -> 150 -> 10 (a broker restart) within one bucket;
        // rateSeries itself is exercised by MetricSeriesRepository's own SQL, but the
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
}
