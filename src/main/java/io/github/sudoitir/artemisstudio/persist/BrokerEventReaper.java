package io.github.sudoitir.artemisstudio.persist;

import io.github.sudoitir.artemisstudio.config.ArtemisStudioProperties;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hourly trim of {@code broker_event} rows past the retention window (ADR-0028).
 * Volume is driven by broker chatter Studio does not control, so this is the
 * containment alongside the writer's bounded buffer. Mirrors
 * {@link MetricSampleReaper}.
 */
@Component
@Slf4j
public class BrokerEventReaper {

    private static final String DELETE_OLD =
            "DELETE FROM broker_event WHERE received_at < now() - make_interval(hours => :hours)";

    private final NamedParameterJdbcTemplate jdbc;
    private volatile int retentionHours;

    public BrokerEventReaper(NamedParameterJdbcTemplate jdbc, ArtemisStudioProperties properties) {
        this.jdbc = jdbc;
        this.retentionHours = Math.max(1, (int) properties.events().retention().toHours());
    }

    /** Runtime override hook — {@code SettingsService} calls this when the window changes. */
    public void setRetentionHours(int retentionHours) {
        this.retentionHours = Math.max(1, retentionHours);
    }

    public int retentionHours() {
        return retentionHours;
    }

    @Scheduled(cron = "0 15 * * * *")
    @Transactional
    public void reap() {
        int deleted = jdbc.update(DELETE_OLD, Map.of("hours", retentionHours));
        if (deleted > 0) {
            log.info("Reaped {} broker_event rows older than {}h", deleted, retentionHours);
        }
    }
}
