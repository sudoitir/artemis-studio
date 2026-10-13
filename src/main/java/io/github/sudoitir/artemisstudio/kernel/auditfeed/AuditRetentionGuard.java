package io.github.sudoitir.artemisstudio.kernel.auditfeed;

import io.github.sudoitir.artemisstudio.kernel.audit.AuditHold;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditPosition;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditRetentionHold;
import io.github.sudoitir.artemisstudio.kernel.auditfeed.AuditReceivers.Attached;
import io.github.sudoitir.artemisstudio.kernel.core.SecretRedactor;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginInstallStatus;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the audit purge must wait for and leave alone (ADR-0190). It does not delete the records a
 * receiver has not acknowledged until they are older than {@code audit.delivery.max-unacknowledged-age},
 * nor any record a plugin's retention hold covers. When it deletes records a receiver never
 * acknowledged, the receiver gets a gap.
 */
@Component
@RequiredArgsConstructor
public class AuditRetentionGuard {

    /** The most holds one plugin may name; a plugin that names more is refused rather than trusted. */
    static final int MAX_HOLDS = 200;

    private static final String DESIRED_ACTIVE =
            PluginInstallStatus.desiredActiveDbValues().stream().collect(Collectors.joining(",", "{", "}"));

    /**
     * What one purge batch must respect.
     *
     * @param floor the lowest position any receiver has acknowledged, null when there is no receiver
     * @param maxAgeCutoff records older than this are purged whether or not a receiver has them
     * @param holds every record they cover is kept
     */
    public record Plan(AuditPosition floor, Instant maxAgeCutoff, List<AuditHold> holds) {

        public Plan {
            holds = List.copyOf(holds);
        }
    }

    /** A record the purge removed. */
    public record Purged(AuditPosition position, Instant ts) {}

    private final JdbcTemplate jdbc;
    private final AuditReceivers receivers;
    private final PluginCalls calls;
    private final SettingsService settings;
    private final Clock clock;

    /**
     * Asks every plugin that declares a retention hold, each call bounded by {@code audit.delivery.timeout}.
     *
     * @throws AuditPurgeRefused when a declared plugin is not running on this replica, throws, times out or names more than 200 holds
     */
    public Plan plan() {
        List<AuditHold> holds = new ArrayList<>();
        for (String pluginId : jdbc.queryForList("""
                SELECT id FROM plugin_install
                WHERE status = ANY (?::text[]) AND coalesce((descriptor->'audit'->>'retentionHold')::boolean, false)
                ORDER BY id""", String.class, DESIRED_ACTIVE)) {
            holds.addAll(holdsOf(pluginId));
        }
        return new Plan(
                floor(), clock.instant().minus(settings.duration(AuditFeedSettings.MAX_UNACKNOWLEDGED_AGE)), holds);
    }

    private List<AuditHold> holdsOf(String pluginId) {
        Attached<AuditRetentionHold> hold = receivers.hold(pluginId);
        if (hold == null) {
            throw new AuditPurgeRefused(
                    "Plugin \"%s\" holds audit records back but is not running on this replica, so nothing was purged"
                            .formatted(pluginId));
        }
        List<AuditHold> answer;
        try {
            answer = calls.call(hold.handle(), hold.bean()::holds, settings.duration(AuditFeedSettings.TIMEOUT));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AuditPurgeRefused(
                    "Interrupted while asking plugin \"%s\" what to hold back".formatted(pluginId), e);
        } catch (Exception e) {
            throw new AuditPurgeRefused(
                    "Plugin \"%s\" could not say what to hold back, so nothing was purged: %s"
                            .formatted(pluginId, SecretRedactor.redact(String.valueOf(e.getMessage()))),
                    e);
        }
        if (answer == null || answer.size() > MAX_HOLDS) {
            throw new AuditPurgeRefused("Plugin \"%s\" named %s holds, more than the %d allowed, so nothing was purged"
                    .formatted(pluginId, answer == null ? "no list of" : answer.size(), MAX_HOLDS));
        }
        return answer;
    }

    private AuditPosition floor() {
        return jdbc
                .query(
                        "SELECT pos_txid::text, pos_seq FROM audit_receiver ORDER BY pos_txid, pos_seq LIMIT 1",
                        (rs, i) -> new AuditPosition(Long.parseLong(rs.getString(1)), rs.getLong(2)))
                .stream()
                .findFirst()
                .orElse(null);
    }

    /**
     * Records that {@code purged} was removed: a gap for every receiver that had not acknowledged
     * some of it, and the time the trail is complete from. Joins the purge's transaction, so a
     * record is never gone without its gap.
     */
    @Transactional
    public void recordPurged(List<Purged> purged) {
        if (purged.isEmpty()) {
            return;
        }
        Instant newest =
                purged.stream().map(Purged::ts).max(Comparator.naturalOrder()).orElseThrow();
        jdbc.update(
                "UPDATE audit_feed_state SET retained_from = greatest(coalesce(retained_from, ?), ?)",
                Timestamp.from(newest),
                Timestamp.from(newest));
        jdbc.query("SELECT plugin_id, pos_txid::text, pos_seq FROM audit_receiver", rs -> {
            AuditPosition cursor = new AuditPosition(Long.parseLong(rs.getString(2)), rs.getLong(3));
            List<Purged> behind = purged.stream()
                    .filter(p -> p.position().compareTo(cursor) > 0)
                    .toList();
            if (!behind.isEmpty()) {
                addGap(rs.getString(1), behind);
            }
        });
    }

    /** Folds into the gap not yet audited or delivered, so a long purge is one gap rather than one per batch. */
    private void addGap(String pluginId, List<Purged> behind) {
        Timestamp first = Timestamp.from(
                behind.stream().map(Purged::ts).min(Comparator.naturalOrder()).orElseThrow());
        Timestamp last = Timestamp.from(
                behind.stream().map(Purged::ts).max(Comparator.naturalOrder()).orElseThrow());
        int extended = jdbc.update("""
                UPDATE audit_receiver_gap
                SET count = count + ?, first_ts = least(first_ts, ?), last_ts = greatest(last_ts, ?)
                WHERE plugin_id = ? AND audited_at IS NULL AND delivered_at IS NULL""", behind.size(), first, last, pluginId);
        if (extended == 0) {
            jdbc.update(
                    "INSERT INTO audit_receiver_gap (count, first_ts, last_ts, plugin_id) VALUES (?, ?, ?, ?)",
                    behind.size(),
                    first,
                    last,
                    pluginId);
        }
    }
}
