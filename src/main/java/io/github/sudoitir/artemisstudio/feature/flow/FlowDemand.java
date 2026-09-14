package io.github.sudoitir.artemisstudio.feature.flow;

import io.github.sudoitir.artemisstudio.kernel.settings.SettingsService;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Who is watching (ADR-0081 D2). A flow read counts its cluster as observed for one lease; an open
 * flow view re-reads well inside the lease, so sampling continues exactly while one is open, on
 * whichever instance serves it.
 *
 * <p>Not the {@code flow} stream topic: every cluster page subscribes to the topics of every
 * enabled feature, so a subscriber says nothing about whether the flow view is open.
 */
@Component
@RequiredArgsConstructor
public class FlowDemand {

    private final FlowStore store;
    private final SettingsService settings;
    private final Clock clock;

    /** Count {@code clusterId} as observed for one more lease from now. */
    public void renew(UUID clusterId) {
        store.renew(clusterId, clock.instant().plus(settings.duration(FlowSettings.DEMAND_LEASE)));
    }
}
