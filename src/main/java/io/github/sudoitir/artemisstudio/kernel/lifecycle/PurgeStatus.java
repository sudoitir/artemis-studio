package io.github.sudoitir.artemisstudio.kernel.lifecycle;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** The last purge of each store ({@code lifecycle_purge}), shared by every instance. */
@Component
public class PurgeStatus {

    /** One store's last purge; {@code error} is {@code null} when it succeeded. */
    public record Last(Instant at, long purged, String error) {}

    private final JdbcTemplate jdbc;

    PurgeStatus(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void recordRun(String storeId, Instant at, long purged, String error) {
        jdbc.update("""
                INSERT INTO lifecycle_purge (store_id, last_run_at, last_purged, last_error) VALUES (?, ?, ?, ?)
                ON CONFLICT (store_id) DO UPDATE
                SET last_run_at = excluded.last_run_at, last_purged = excluded.last_purged, last_error = excluded.last_error""", storeId, Timestamp.from(at), purged, error);
    }

    public Map<String, Last> all() {
        return jdbc
                .query(
                        "SELECT store_id, last_run_at, last_purged, last_error FROM lifecycle_purge",
                        (rs, i) -> Map.entry(
                                rs.getString(1),
                                new Last(rs.getTimestamp(2).toInstant(), rs.getLong(3), rs.getString(4))))
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
