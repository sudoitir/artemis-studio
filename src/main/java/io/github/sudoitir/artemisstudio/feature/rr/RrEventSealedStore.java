package io.github.sudoitir.artemisstudio.feature.rr;

import io.github.sudoitir.artemisstudio.kernel.security.SealedStore;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** The sealed originals kept in {@code rr_event.detail} as base64, for key rotation (ADR-0132). */
@Component
class RrEventSealedStore implements SealedStore {

    private static final String SEALED = "detail->>'sealed'";
    private static final String VERSION = SealedStore.versionOf("decode(" + SEALED + ", 'base64')");

    private final JdbcTemplate jdbc;

    RrEventSealedStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "rr_event";
    }

    @Override
    public long countBelow(int version) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM rr_event WHERE " + SEALED + " IS NOT NULL AND " + VERSION + " < ?",
                Long.class,
                version);
        return count == null ? 0 : count;
    }

    @Override
    public Batch rewrapBatch(
            Object after, int belowVersion, int targetVersion, int limit, UnaryOperator<byte[]> rewrap) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT seq, " + SEALED + " AS sealed FROM rr_event WHERE seq > ? AND " + SEALED + " IS NOT NULL AND "
                        + VERSION + " < ? ORDER BY seq LIMIT ?",
                after == null ? Long.MIN_VALUE : after,
                belowVersion,
                limit);
        int updated = 0;
        for (Map<String, Object> row : rows) {
            Object seq = row.get("seq");
            String old = (String) row.get("sealed");
            String rewrapped;
            try {
                rewrapped = Base64.getEncoder()
                        .encodeToString(rewrap.apply(Base64.getDecoder().decode(old)));
            } catch (RuntimeException e) {
                throw new RewrapException(name(), "seq=" + seq, e);
            }
            updated += jdbc.update(
                    "UPDATE rr_event SET detail = jsonb_set(detail, '{sealed}', to_jsonb(?::text))"
                            + " WHERE seq = ? AND " + SEALED + " = ?",
                    rewrapped,
                    seq,
                    old);
        }
        return new Batch(updated, rows.size() < limit ? null : rows.getLast().get("seq"));
    }

    @Override
    public Map<Integer, Long> countByVersion() {
        Map<Integer, Long> counts = new TreeMap<>();
        jdbc.query(
                "SELECT " + VERSION + " AS version, count(*) AS n FROM rr_event WHERE " + SEALED
                        + " IS NOT NULL GROUP BY 1",
                rs -> {
                    counts.put(rs.getInt("version"), rs.getLong("n"));
                });
        return counts;
    }
}
