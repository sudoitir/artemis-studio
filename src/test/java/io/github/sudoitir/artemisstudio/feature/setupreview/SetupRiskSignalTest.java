package io.github.sudoitir.artemisstudio.feature.setupreview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.feature.setupreview.internal.persistence.SetupFindingAcceptanceEntity;
import io.github.sudoitir.artemisstudio.feature.setupreview.internal.persistence.SetupFindingAcceptanceRepository;
import io.github.sudoitir.artemisstudio.feature.setupreview.internal.persistence.SetupFindingEntity;
import io.github.sudoitir.artemisstudio.feature.setupreview.internal.persistence.SetupFindingRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SetupRiskSignalTest {

    private final UUID clusterId = UUID.randomUUID();

    private SetupFindingEntity finding(String code, String severity) {
        SetupFindingEntity f =
                new SetupFindingEntity(clusterId, code, "cluster", Instant.now().minusSeconds(3600));
        f.seen(severity, "HIGH_AVAILABILITY", "{}", Instant.now().minusSeconds(3600)); // stale is still here
        return f;
    }

    @Test
    void openAcceptedExpiredAndInfo() {
        SetupFindingRepository findings = mock(SetupFindingRepository.class);
        SetupFindingAcceptanceRepository acceptances = mock(SetupFindingAcceptanceRepository.class);
        when(findings.findByClusterId(clusterId))
                .thenReturn(List.of(
                        finding("OPEN", "CRITICAL"),
                        finding("ACCEPTED", "WARNING"),
                        finding("EXPIRED", "WARNING"),
                        finding("HARDENING", "INFO")));
        Instant now = Instant.now();
        when(acceptances.findByClusterId(clusterId))
                .thenReturn(List.of(
                        new SetupFindingAcceptanceEntity(
                                clusterId, "ACCEPTED", "cluster", "dev only", "ops", now, null),
                        new SetupFindingAcceptanceEntity(
                                clusterId,
                                "EXPIRED",
                                "cluster",
                                "until the upgrade",
                                "ops",
                                now.minus(Duration.ofDays(2)),
                                now.minus(Duration.ofDays(1)))));

        var evaluation = new SetupRiskSignal(findings, acceptances).evaluate(clusterId);

        assertThat(evaluation.universe())
                .containsExactlyInAnyOrder("setup:OPEN:cluster", "setup:ACCEPTED:cluster", "setup:EXPIRED:cluster");
        assertThat(evaluation.active()).containsOnlyKeys("setup:OPEN:cluster", "setup:EXPIRED:cluster");
        assertThat(evaluation.active().get("setup:OPEN:cluster")).isEqualTo(2.0);
    }
}
