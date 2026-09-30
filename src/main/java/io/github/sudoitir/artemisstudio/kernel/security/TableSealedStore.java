package io.github.sudoitir.artemisstudio.kernel.security;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import org.springframework.jdbc.core.JdbcTemplate;

/** A {@link SealedStore} for a table with a {@code sealed bytea} column and a primary key. */
public class TableSealedStore implements SealedStore {

    private static final String VERSION = SealedStore.versionOf("sealed");

    private final JdbcTemplate jdbc;
    private final String table;
    private final List<String> key;
    private final String selectFirst;
    private final String selectAfter;
    private final String update;

    public TableSealedStore(JdbcTemplate jdbc, String table, String... keyColumns) {
        this.jdbc = jdbc;
        this.table = table;
        this.key = List.of(keyColumns);
        String keys = String.join(", ", key);
        String head = "SELECT " + keys + ", sealed FROM " + table + " WHERE sealed IS NOT NULL AND " + VERSION + " < ?";
        String tail = " ORDER BY " + keys + " LIMIT ?";
        this.selectFirst = head + tail;
        this.selectAfter = head + " AND (" + keys + ") > ("
                + String.join(", ", key.stream().map(c -> "?").toList()) + ")" + tail;
        this.update = "UPDATE " + table + " SET sealed = ? WHERE "
                + String.join(" AND ", key.stream().map(c -> c + " = ?").toList()) + " AND sealed = ?";
    }

    @Override
    public String name() {
        return table;
    }

    @Override
    public long countBelow(int version) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE sealed IS NOT NULL AND " + VERSION + " < ?",
                Long.class,
                version);
        return count == null ? 0 : count;
    }

    @Override
    public Batch rewrapBatch(Object after, int targetVersion, int limit, UnaryOperator<byte[]> rewrap) {
        List<Object> select = new ArrayList<>();
        select.add(targetVersion);
        if (after != null) {
            select.addAll((List<?>) after);
        }
        select.add(limit);
        List<Row> rows = jdbc.query(
                after == null ? selectFirst : selectAfter,
                (rs, i) -> {
                    Object[] values = new Object[key.size()];
                    for (int c = 0; c < values.length; c++) {
                        values[c] = rs.getObject(c + 1);
                    }
                    return new Row(Arrays.asList(values), ByteBuffer.wrap(rs.getBytes(values.length + 1)));
                },
                select.toArray());
        int updated = 0;
        for (Row row : rows) {
            byte[] rewrapped;
            try {
                rewrapped = rewrap.apply(row.sealed().array());
            } catch (RuntimeException e) {
                throw new RewrapException(table, row.key().toString(), e);
            }
            List<Object> args = new ArrayList<>();
            args.add(rewrapped);
            args.addAll(row.key());
            args.add(row.sealed().array());
            updated += jdbc.update(update, args.toArray());
        }
        return new Batch(updated, rows.size() < limit ? null : rows.getLast().key());
    }

    @Override
    public Map<Integer, Long> countByVersion() {
        Map<Integer, Long> counts = new TreeMap<>();
        jdbc.query(
                "SELECT " + VERSION + " AS version, count(*) AS n FROM " + table
                        + " WHERE sealed IS NOT NULL GROUP BY 1",
                rs -> {
                    int version = rs.getInt("version");
                    if (!rs.wasNull()) {
                        counts.put(version, rs.getLong("n"));
                    }
                });
        return counts;
    }

    /** {@code sealed} is a buffer, not an array, so the record compares by content. */
    private record Row(List<Object> key, ByteBuffer sealed) {}
}
