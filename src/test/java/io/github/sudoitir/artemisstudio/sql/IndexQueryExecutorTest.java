package io.github.sudoitir.artemisstudio.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.artemisstudio.persist.MessageIndexWriter;
import io.github.sudoitir.artemisstudio.sql.QueryAst.Source;
import io.github.sudoitir.artemisstudio.sql.QueryPlan.Target;
import io.github.sudoitir.artemisstudio.sql.QueryResult.NodeOutcome;
import io.github.sudoitir.artemisstudio.sql.QueryResult.Row;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The index against a real PostgreSQL, because the parts worth testing are the parts
 * only Postgres implements: the trigram index behind {@code body LIKE '%…%'}, JSONB
 * property extraction, and the fact that an indexed message outlives the broker's
 * copy of it — which is the whole reason the index exists.
 */
class IndexQueryExecutorTest extends PostgresIntegrationTest {

    private static final UUID CLUSTER = UUID.randomUUID();
    private static final UUID NODE = UUID.randomUUID();

    @Autowired
    private IndexQueryExecutor executor;

    @Autowired
    private MessageIndexWriter writer;

    @Autowired
    private SqlQueryParser parser;

    @Autowired
    private JdbcTemplate jdbc;

    /** Collects what the executor streamed, so a test asserts what a client would see. */
    private static final class Collect implements BrokerQueryExecutor.Sink {
        private final List<Row> rows = new ArrayList<>();

        @Override
        public void row(Row row) {
            rows.add(row);
        }

        @Override
        public void nodeFinished(NodeOutcome outcome) {}

        @Override
        public boolean isCancelled() {
            return false;
        }
    }

    @BeforeEach
    void clear() {
        jdbc.update("DELETE FROM message_index WHERE cluster_id = ?", CLUSTER);
    }

    private Row message(long id, String body, Map<String, Object> properties) {
        return new Row(
                NODE,
                "primary",
                "ORDER.IN",
                "ORDER.IN",
                id,
                3,
                true,
                4,
                Instant.now().toEpochMilli(),
                0,
                body == null ? 0 : body.length(),
                null,
                null,
                null,
                null,
                null,
                body,
                false,
                properties,
                Source.BROKER,
                null,
                null,
                null,
                null);
    }

    private QueryPlan plan(String sql) {
        QueryAst ast = parser.parse(sql);
        Target target = new Target(NODE, "primary", "ORDER.IN", "ORDER.IN", "ANYCAST", 0, Instant.now(), true);
        return new QueryPlan(
                ast,
                Source.INDEX,
                List.of(target),
                null,
                false,
                List.of(),
                List.of(),
                0,
                ast.limit() == null ? 100 : ast.limit(),
                false,
                List.of());
    }

    private List<Row> run(String sql) {
        Collect sink = new Collect();
        executor.execute(CLUSTER, plan(sql), sink);
        return sink.rows;
    }

    @Test
    void findsAMessageByABodyFragment() {
        writer.observe(CLUSTER, message(1, "{\"orderId\":\"4471\",\"total\":12}", Map.of()), Instant.now());
        writer.observe(CLUSTER, message(2, "{\"orderId\":\"9902\"}", Map.of()), Instant.now());

        List<Row> rows = run("SELECT * FROM index.\"ORDER.IN\" WHERE body LIKE '%4471%' LIMIT 10");

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.messageId()).isEqualTo(1);
            // Provenance travels with the row: an INDEX row is one that may have been
            // consumed since, and the client must never have to infer that.
            assertThat(row.source()).isEqualTo(Source.INDEX);
            assertThat(row.observedAt()).isNotNull();
        });
    }

    @Test
    void findsAMessageByAnApplicationProperty() {
        writer.observe(CLUSTER, message(3, "one", Map.of("tenant", "acme", "attempt", 2)), Instant.now());
        writer.observe(CLUSTER, message(4, "two", Map.of("tenant", "globex")), Instant.now());

        assertThat(run("SELECT * FROM index.\"ORDER.IN\" WHERE props.tenant = 'acme' LIMIT 10"))
                .singleElement()
                .satisfies(row -> assertThat(row.properties()).containsEntry("tenant", "acme"));
    }

    @Test
    void findsAMessageByAJsonBodyPath() {
        writer.observe(CLUSTER, message(5, "{\"order\":{\"id\":\"4471\"}}", Map.of()), Instant.now());

        assertThat(run("SELECT * FROM index.\"ORDER.IN\" WHERE body->>'order.id' = '4471' LIMIT 10"))
                .hasSize(1);
    }

    @Test
    void stillAnswersForAMessageTheBrokerNoLongerHas() {
        // The index is written from an observation, and nothing about a later
        // consumption reaches it. That is the feature: the row survives, carrying when
        // it was seen, and "is it still there?" is a separate question with its own
        // answer (MessageVerifier).
        writer.observe(CLUSTER, message(6, "consumed later", Map.of()), Instant.now());

        assertThat(run("SELECT * FROM index.\"ORDER.IN\" LIMIT 10"))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.messageId()).isEqualTo(6);
                    assertThat(row.lastSeenAt()).isNotNull();
                });
    }

    @Test
    void aSecondObservationAdvancesLastSeenWithoutDuplicatingTheMessage() {
        Instant first = Instant.now().minusSeconds(60);
        assertThat(writer.observe(CLUSTER, message(7, "same message", Map.of()), first))
                .isTrue();
        assertThat(writer.observe(CLUSTER, message(7, "same message", Map.of()), Instant.now()))
                .isFalse();

        assertThat(run("SELECT * FROM index.\"ORDER.IN\" LIMIT 10"))
                .singleElement()
                .satisfies(row -> assertThat(row.lastSeenAt()).isAfter(row.observedAt()));
    }

    @Test
    void reportsTheRowLimitItStoppedAt() {
        for (long id = 10; id < 15; id++) {
            writer.observe(CLUSTER, message(id, "body " + id, Map.of()), Instant.now());
        }

        Collect sink = new Collect();
        QueryResult result = executor.execute(CLUSTER, plan("SELECT * FROM index.\"ORDER.IN\" LIMIT 2"), sink);

        assertThat(sink.rows).hasSize(2);
        // A truncated result that does not say it was truncated is read as complete.
        assertThat(result.boundsReached())
                .singleElement()
                .satisfies(bound -> assertThat(bound.kind()).isEqualTo(QueryResult.Bound.Kind.ROW_LIMIT));
    }

    // ---- full text (ADR-0063) -------------------------------------------

    @Test
    void findsAQuotedPhraseAndHonoursAnExclusion() {
        writer.observe(CLUSTER, message(20, "order 4471 shipped to acme", Map.of()), Instant.now());
        writer.observe(CLUSTER, message(21, "order 4471 cancelled by acme", Map.of()), Instant.now());
        writer.observe(CLUSTER, message(22, "order 9902 shipped", Map.of()), Instant.now());

        assertThat(run("SELECT * FROM index.\"ORDER.IN\" WHERE MATCH (body) AGAINST ('\"order 4471\"') LIMIT 10"))
                .extracting(Row::messageId)
                .containsExactlyInAnyOrder(20L, 21L);

        // `-word` excludes, the way it does in a search box. websearch_to_tsquery is
        // what makes that free rather than a dialect of its own.
        assertThat(
                        run(
                                "SELECT * FROM index.\"ORDER.IN\" WHERE MATCH (body) AGAINST ('order 4471 -cancelled') LIMIT 10"))
                .extracting(Row::messageId)
                .containsExactly(20L);
    }

    @Test
    void ordersByHowWellEachRowMatched() {
        writer.observe(CLUSTER, message(30, "acme", Map.of()), Instant.now());
        writer.observe(CLUSTER, message(31, "acme acme acme order acme", Map.of()), Instant.now());

        assertThat(run("SELECT * FROM index.\"ORDER.IN\" WHERE MATCH (body) AGAINST ('acme')"
                        + " ORDER BY match_rank DESC LIMIT 10"))
                .extracting(Row::messageId)
                .containsExactly(31L, 30L);
    }

    @Test
    void refusesToRankWithoutSomethingToRankAgainst() {
        assertThatThrownBy(() -> run("SELECT * FROM index.\"ORDER.IN\" ORDER BY match_rank DESC LIMIT 10"))
                .isInstanceOf(SqlSyntaxException.class)
                .hasMessageContaining("no MATCH()");
    }

    @Test
    void aBinaryBodyIsNotFullTextSearchable() {
        // A BytesMessage body is stored base64, and to_tsvector over base64 is garbage
        // that would bloat the index for no retrieval value — so it is not indexed, and
        // the query's own predicate is what keeps that consistent rather than accidental.
        Row binary = new Row(
                NODE,
                "primary",
                "ORDER.IN",
                "ORDER.IN",
                40,
                4,
                true,
                4,
                Instant.now().toEpochMilli(),
                0,
                4,
                null,
                null,
                null,
                null,
                null,
                "acme",
                false,
                Map.of(),
                Source.BROKER,
                null,
                null,
                null,
                null);
        writer.observe(CLUSTER, binary, Instant.now());

        assertThat(run("SELECT * FROM index.\"ORDER.IN\" WHERE MATCH (body) AGAINST ('acme') LIMIT 10"))
                .isEmpty();
    }

    @Test
    void theFunctionalGinIndexIsActuallyChosen() {
        // A full-text feature that silently sequential-scans is worse than none: it
        // looks like it works right up to the volume where it matters.
        for (long id = 100; id < 400; id++) {
            writer.observe(CLUSTER, message(id, "order " + id + " shipped to acme", Map.of()), Instant.now());
        }
        jdbc.execute("ANALYZE message_index");

        String plan = String.join(
                "\n",
                jdbc.queryForList(
                        "EXPLAIN SELECT * FROM message_index WHERE cluster_id = ?"
                                + " AND (message_type <> 4 AND to_tsvector('simple', body)"
                                + " @@ websearch_to_tsquery('simple', ?))",
                        String.class,
                        CLUSTER,
                        "order 250"));

        // The partition's own child index carries a generated name, so the assertion
        // is on the access path rather than on the parent index's name: a Bitmap Index
        // Scan whose condition is the tsvector match is exactly "the GIN index was
        // used", and a sequential scan could not produce either line.
        assertThat(plan).contains("Bitmap Index Scan").contains("to_tsvector");
        // Tomorrow's partitions are empty and are scanned at zero cost — that is
        // Postgres being right, not the index being missed. What matters is that the
        // partition actually holding the rows is reached through the index.
        String populated = plan.lines()
                .filter(line -> line.contains("Bitmap Index Scan"))
                .findFirst()
                .orElse("");
        assertThat(populated).contains("to_tsvector");
    }
}
