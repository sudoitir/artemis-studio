package io.github.sudoitir.artemisstudio.feature.rr;

import io.github.sudoitir.artemisstudio.kernel.lifecycle.HousekeepingContributor;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.ManagedStore;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Puts request-reply flows and their captured payloads under the data lifecycle (ADR-0134). */
@Component
class RrHousekeeping implements HousekeepingContributor {

    private final List<ManagedStore> stores;

    RrHousekeeping(JdbcTemplate jdbc) {
        stores = List.of(new RrFlowStore(jdbc), new CapturedPayloadStore(jdbc));
    }

    @Override
    public List<ManagedStore> stores() {
        return stores;
    }
}
