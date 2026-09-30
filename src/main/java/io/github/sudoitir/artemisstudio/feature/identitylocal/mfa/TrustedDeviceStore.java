package io.github.sudoitir.artemisstudio.feature.identitylocal.mfa;

import io.github.sudoitir.artemisstudio.kernel.lifecycle.HousekeepingContributor;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.LifecycleSql;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.ManagedStore;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.PurgeEstimate;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.StoreDef;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.StoreUsage;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Expired trusted devices under the data lifecycle (ADR-0134, ADR-0142). A device stops counting at its
 * expiry, so the retention is only the grace after it, and a purge never shortens a device that still counts.
 */
@Component
@RequiredArgsConstructor
class TrustedDeviceStore implements HousekeepingContributor, ManagedStore {

    private static final String TABLE = "local_trusted_device";
    private static final String EXPIRED = "expires_at < ?";

    private static final StoreDef DEF = new StoreDef(
            "trusted-devices",
            "Expired trusted devices",
            List.of(TABLE),
            StoreDef.QuotaUnit.ROWS,
            Duration.ofDays(1),
            Duration.ofMinutes(1),
            Duration.ofDays(30));

    private final JdbcTemplate jdbc;

    @Override
    public List<ManagedStore> stores() {
        return List.of(this);
    }

    @Override
    public StoreDef def() {
        return DEF;
    }

    @Override
    public StoreUsage usage() {
        return LifecycleSql.usage(jdbc, DEF.tables());
    }

    @Override
    public PurgeEstimate preview(Instant cutoff) {
        return LifecycleSql.estimate(jdbc, TABLE, EXPIRED, cutoff);
    }

    @Override
    public long purgeBatch(Instant cutoff, int limit) {
        return LifecycleSql.deleteBatch(jdbc, TABLE, EXPIRED, cutoff, limit);
    }
}
