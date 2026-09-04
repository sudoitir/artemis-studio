package io.github.sudoitir.artemisstudio.persist;

import io.github.sudoitir.artemisstudio.config.ArtemisStudioProperties;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Nightly trim of {@code rr_flow} rows past the retention window
 * ({@code artemis-studio.rr.retention}). {@code rr_event} cascades on
 * {@code rr_flow} deletion (007-request-reply.sql), so deleting the flow is
 * enough. Mirrors {@link BrokerEventReaper}.
 */
@Component
@Slf4j
public class RrFlowReaper {

    private static final String DELETE_OLD =
            "DELETE FROM rr_flow WHERE requested_at < now() - make_interval(days => :days)";

    private final NamedParameterJdbcTemplate jdbc;
    private volatile int retentionDays;

    public RrFlowReaper(NamedParameterJdbcTemplate jdbc, ArtemisStudioProperties properties) {
        this.jdbc = jdbc;
        this.retentionDays = Math.max(1, (int) properties.rr().retention().toDays());
    }

    @Scheduled(cron = "0 20 3 * * *")
    @Transactional
    public void reap() {
        int deleted = jdbc.update(DELETE_OLD, Map.of("days", retentionDays));
        if (deleted > 0) {
            log.info("Reaped {} rr_flow rows older than {}d", deleted, retentionDays);
        }
    }
}
