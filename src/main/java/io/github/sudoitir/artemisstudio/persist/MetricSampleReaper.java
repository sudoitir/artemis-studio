package io.github.sudoitir.artemisstudio.persist;

import io.github.sudoitir.artemisstudio.config.ArtemisStudioProperties;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Nightly trim of raw {@code metric_sample} rows past the retention window
 * (ADR-0006 — 7-day default). Daily partitioning is still Phase 6; until then a
 * bounded {@code DELETE} against the default partition keeps the cache from
 * growing without limit.
 */
@Component
@Slf4j
public class MetricSampleReaper {

    private static final String DELETE_OLD =
            "DELETE FROM metric_sample WHERE ts < now() - make_interval(days => :days)";

    private final NamedParameterJdbcTemplate jdbc;
    private volatile int retentionDays;

    public MetricSampleReaper(NamedParameterJdbcTemplate jdbc, ArtemisStudioProperties properties) {
        this.jdbc = jdbc;
        this.retentionDays = Math.max(1, properties.metric().retentionDays());
    }

    /** Runtime override hook — {@code SettingsService} calls this when the window changes. */
    public void setRetentionDays(int retentionDays) {
        this.retentionDays = Math.max(1, retentionDays);
    }

    public int retentionDays() {
        return retentionDays;
    }

    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void reap() {
        int deleted = jdbc.update(DELETE_OLD, Map.of("days", retentionDays));
        if (deleted > 0) {
            log.info("Reaped {} metric_sample rows older than {} days", deleted, retentionDays);
        }
    }
}
