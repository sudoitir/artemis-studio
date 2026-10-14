package io.github.sudoitir.rahdar.feature.rr;

import io.github.sudoitir.rahdar.kernel.lifecycle.HousekeepingContributor;
import io.github.sudoitir.rahdar.kernel.lifecycle.ManagedStore;
import io.github.sudoitir.rahdar.kernel.lifecycle.partition.PartitionDrops;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Puts request-reply flows and their captured payloads under the data lifecycle (ADR-0134). */
@Component
class RrHousekeeping implements HousekeepingContributor {

    private final List<ManagedStore> stores;

    RrHousekeeping(JdbcTemplate jdbc, PartitionDrops drops) {
        stores = List.of(new RrFlowStore(jdbc, drops), new CapturedPayloadStore(jdbc));
    }

    @Override
    public List<ManagedStore> stores() {
        return stores;
    }
}
