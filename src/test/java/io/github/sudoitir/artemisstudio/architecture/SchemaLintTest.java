package io.github.sudoitir.artemisstudio.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The rules every table of the migrated schema follows (ADR-0192, capability {@code database-schema}),
 * read from the catalog of the database Liquibase has just built. A table that must break a rule is
 * named in {@link #ALLOWED} with the reason, and an entry the schema no longer needs fails the test,
 * so the list only ever holds live exceptions.
 */
class SchemaLintTest extends PostgresIntegrationTest {

    private enum Rule {
        PRIMARY_KEY("has no primary key"),
        COLUMN_TYPE("uses json, timestamp without time zone or varchar(n)"),
        COLUMN_DEFAULT("defaults a column from gen_random_uuid() or a serial sequence"),
        UUID_KEY_DEFAULT("has a uuid primary key whose default is not uuidv7()"),
        IDENTITY_KEY("has a bigint primary key that is not an identity"),
        FOREIGN_KEY_INDEX("has a foreign key no index leads with"),
        DEFAULT_PARTITION("has a default partition");

        private final String text;

        Rule(String text) {
            this.text = text;
        }
    }

    private record Allowed(String table, Rule rule, String reason) {}

    /** Every exception to the rules, each with why. */
    private static final List<Allowed> ALLOWED = List.of(
            new Allowed("databasechangelog", Rule.PRIMARY_KEY, "Liquibase's own history table"),
            new Allowed("databasechangelog", Rule.COLUMN_TYPE, "Liquibase's own history table"),
            new Allowed("shedlock", Rule.COLUMN_TYPE, "ShedLock's own table, created in the shape its library expects"),
            new Allowed(
                    "spring_session",
                    Rule.COLUMN_TYPE,
                    "Spring Session's table, created in the shape its library expects"),
            new Allowed(
                    "spring_session_attributes",
                    Rule.COLUMN_TYPE,
                    "Spring Session's table, created in the shape its library expects"),
            new Allowed(
                    "sql_query_ticket",
                    Rule.UUID_KEY_DEFAULT,
                    "a bearer handle: its random UUIDv4 is the credential, so the database never invents one"),
            new Allowed(
                    "diagnostics_snapshot",
                    Rule.UUID_KEY_DEFAULT,
                    "a bearer handle: its random UUIDv4 is the credential, so the database never invents one"),
            new Allowed(
                    "request_in_flight",
                    Rule.UUID_KEY_DEFAULT,
                    "keyed by the id of the token or user it counts, which the limiter supplies; it names no row of its own"),
            new Allowed(
                    "upload_window",
                    Rule.UUID_KEY_DEFAULT,
                    "keyed by the id of the user it counts, which the limiter supplies; it names no row of its own"),
            new Allowed("message_index", Rule.DEFAULT_PARTITION, "removed by the partition maintainer unit"),
            new Allowed("metric_sample", Rule.DEFAULT_PARTITION, "removed by the partition maintainer unit"));

    private static final String SCHEMA = "n.nspname = current_schema()";

    private static final String TABLES = "c.relkind IN ('r', 'p') AND NOT c.relispartition AND " + SCHEMA;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void everyTableFollowsTheRulesOrIsAllowed() {
        List<String> found = new ArrayList<>();
        Set<String> used = new TreeSet<>();
        for (Rule rule : Rule.values()) {
            for (String table : tablesBreaking(rule)) {
                if (ALLOWED.stream().anyMatch(e -> e.table().equals(table) && e.rule() == rule)) {
                    used.add(table + " " + rule);
                } else {
                    found.add(table + " " + rule.text);
                }
            }
        }
        assertThat(found).as("tables that break a schema rule").isEmpty();
        assertThat(ALLOWED)
                .as("allow-list entries the schema still needs")
                .allSatisfy(e -> assertThat(used).contains(e.table() + " " + e.rule()));
    }

    private List<String> tablesBreaking(Rule rule) {
        String sql =
                switch (rule) {
                    case PRIMARY_KEY -> """
                    SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE %s AND NOT EXISTS (SELECT 1 FROM pg_constraint k WHERE k.conrelid = c.oid AND k.contype = 'p')
                    """.formatted(TABLES);
                    case COLUMN_TYPE -> """
                    SELECT DISTINCT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                    JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
                    WHERE %s AND (a.atttypid IN ('json'::regtype, 'timestamp'::regtype)
                        OR format_type(a.atttypid, a.atttypmod) LIKE 'character%%')
                    """.formatted(TABLES);
                    case COLUMN_DEFAULT -> """
                    SELECT DISTINCT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                    JOIN pg_attrdef d ON d.adrelid = c.oid
                    WHERE %s AND (pg_get_expr(d.adbin, d.adrelid) LIKE '%%gen_random_uuid%%'
                        OR pg_get_expr(d.adbin, d.adrelid) LIKE 'nextval(%%')
                    """.formatted(TABLES);
                    // A key that is also a foreign key takes its parent's id, so it has no default of its own.
                    case UUID_KEY_DEFAULT -> """
                    SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                    JOIN pg_constraint k ON k.conrelid = c.oid AND k.contype = 'p' AND cardinality(k.conkey) = 1
                    JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = k.conkey[1]
                    LEFT JOIN pg_attrdef d ON d.adrelid = c.oid AND d.adnum = a.attnum
                    WHERE %s AND a.atttypid = 'uuid'::regtype
                        AND coalesce(pg_get_expr(d.adbin, d.adrelid), '') <> 'uuidv7()'
                        AND NOT EXISTS (SELECT 1 FROM pg_constraint f
                            WHERE f.conrelid = c.oid AND f.contype = 'f' AND a.attnum = ANY (f.conkey))
                    """.formatted(TABLES);
                    case IDENTITY_KEY -> """
                    SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                    JOIN pg_constraint k ON k.conrelid = c.oid AND k.contype = 'p' AND cardinality(k.conkey) = 1
                    JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = k.conkey[1]
                    WHERE %s AND a.atttypid = 'int8'::regtype AND a.attidentity = ''
                    """.formatted(TABLES);
                    case FOREIGN_KEY_INDEX -> """
                    SELECT DISTINCT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                    JOIN pg_constraint k ON k.conrelid = c.oid AND k.contype = 'f'
                    WHERE %s AND NOT EXISTS (SELECT 1 FROM pg_index i WHERE i.indrelid = c.oid AND i.indisvalid
                        AND (i.indkey::int2[])[0:cardinality(k.conkey) - 1] = k.conkey)
                    """.formatted(TABLES);
                    case DEFAULT_PARTITION -> """
                    SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                    JOIN pg_partitioned_table p ON p.partrelid = c.oid
                    WHERE %s AND p.partdefid <> 0
                    """.formatted(TABLES);
                };
        return jdbc.queryForList(sql, String.class);
    }
}
