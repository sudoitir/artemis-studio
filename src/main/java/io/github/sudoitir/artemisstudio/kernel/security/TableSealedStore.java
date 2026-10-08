package io.github.sudoitir.artemisstudio.kernel.security;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A {@link SealedStore} for a table with a sealed {@code bytea} column, {@code sealed} unless named, and a primary
 * key.
 */
public class TableSealedStore implements SealedStore {

    private static final String WHERE = " WHERE ";
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]*");

    private final JdbcTemplate jdbc;
    private final String name;
    private final String table;
    private final String column;
    private final String version;
    private final List<String> key;
    private final String selectFirst;
    private final String selectAfter;
    private final String update;
    private final String countBelow;
    private final String countByVersion;

    public TableSealedStore(JdbcTemplate jdbc, String table, String... keyColumns) {
        this(jdbc, table, "sealed", List.of(keyColumns));
    }

    /** A store for the sealed column {@code column}; a table with several sealed columns has one store for each. */
    public TableSealedStore(JdbcTemplate jdbc, String table, String column, List<String> keyColumns) {
        this.jdbc = jdbc;
        Stream.concat(Stream.of(table, column), keyColumns.stream()).forEach(TableSealedStore::requireIdentifier);
        this.name = "sealed".equals(column) ? table : table + "." + column;
        this.table = table;
        this.column = column;
        this.version = SealedStore.versionOf(column);
        this.key = List.copyOf(keyColumns);
        String keys = String.join(", ", key);
        String sealedBelow = column + " IS NOT NULL AND " + version + " < ?";
        String head = "SELECT " + keys + ", " + column + " FROM " + table + WHERE + sealedBelow;
        String tail = " ORDER BY " + keys + " LIMIT ?";
        this.selectFirst = head + tail;
        this.selectAfter = head + " AND (" + keys + ") > ("
                + String.join(", ", key.stream().map(c -> "?").toList()) + ")" + tail;
        this.update = "UPDATE " + table + " SET " + column + " = ?" + WHERE
                + String.join(" AND ", key.stream().map(c -> c + " = ?").toList()) + " AND " + column + " = ?";
        this.countBelow = "SELECT count(*) FROM " + table + WHERE + sealedBelow;
        this.countByVersion = "SELECT " + version + " AS version, count(*) AS n FROM " + table + WHERE + column
                + " IS NOT NULL GROUP BY 1";
    }

    /** Table and column names are spliced into SQL, so only plain lower-case identifiers are accepted. */
    private static void requireIdentifier(String identifier) {
        if (!IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException("Not a plain SQL identifier: " + identifier);
        }
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public long countBelow(int version) {
        Long count = jdbc.queryForObject(countBelow, Long.class, version);
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
        jdbc.query(countByVersion, rs -> {
            int found = rs.getInt("version");
            if (!rs.wasNull()) {
                counts.put(found, rs.getLong("n"));
            }
        });
        return counts;
    }

    /** {@code sealed} is a buffer, not an array, so the record compares by content. */
    private record Row(List<Object> key, ByteBuffer sealed) {}
}
