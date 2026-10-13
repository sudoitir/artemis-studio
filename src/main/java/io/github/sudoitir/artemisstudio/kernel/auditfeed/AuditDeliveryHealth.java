package io.github.sudoitir.artemisstudio.kernel.auditfeed;

import io.github.sudoitir.artemisstudio.kernel.settings.SettingsService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** How far each audit receiver is behind (ADR-0190), for the alert that says one has fallen too far. */
@Component
@RequiredArgsConstructor
public class AuditDeliveryHealth {

    /**
     * One receiver.
     *
     * @param behind it has a gap it was not told about yet, or its oldest unacknowledged record is
     *     older than {@code audit.delivery.alert-after}
     * @param behindSeconds the age of that oldest unacknowledged record, 0 when there is none
     */
    public record Receiver(String pluginId, boolean behind, double behindSeconds) {}

    private final JdbcTemplate jdbc;
    private final SettingsService settings;
    private final Clock clock;

    /** Every receiver, found with one index lookup each for its oldest record above the cursor. */
    public List<Receiver> receivers() {
        Instant now = clock.instant();
        Duration alertAfter = settings.duration(AuditFeedSettings.ALERT_AFTER);
        return jdbc.query("""
                SELECT r.plugin_id,
                       EXISTS (SELECT 1 FROM audit_receiver_gap g WHERE g.plugin_id = r.plugin_id AND g.delivered_at IS NULL),
                       (SELECT e.ts FROM audit_event e WHERE (e.pos_txid, e.pos_seq) > (r.pos_txid, r.pos_seq)
                        ORDER BY e.pos_txid, e.pos_seq LIMIT 1)
                FROM audit_receiver r ORDER BY r.plugin_id""", (rs, i) -> {
            Timestamp oldest = rs.getTimestamp(3);
            Duration age = oldest == null ? Duration.ZERO : Duration.between(oldest.toInstant(), now);
            return new Receiver(
                    rs.getString(1), rs.getBoolean(2) || age.compareTo(alertAfter) > 0, age.toMillis() / 1000.0);
        });
    }
}
