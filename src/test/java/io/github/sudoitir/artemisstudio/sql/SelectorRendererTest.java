package io.github.sudoitir.artemisstudio.sql;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.sql.ColumnCatalogue.Evaluation;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The renderer is where Studio's text reaches the broker, and the splitter is what
 * decides whether a predicate gets there at all. The cases that matter are the ones
 * where a predicate must <em>not</em> be pushed down — those fail silently, by
 * returning a plausible but wrong set of messages.
 */
class SelectorRendererTest {

    private final SqlQueryParser parser = new SqlQueryParser();
    private final SelectorRenderer renderer = new SelectorRenderer();
    private final PredicateSplitter splitter = new PredicateSplitter(renderer);

    private String selectorFor(String where) {
        QueryAst ast = parser.parse("SELECT * FROM \"q\" WHERE " + where);
        return renderer.render(splitter.split(ast.where()).pushdown(), Instant.ofEpochMilli(1_000_000L));
    }

    private Evaluation classify(String where) {
        QueryAst ast = parser.parse("SELECT * FROM \"q\" WHERE " + where);
        return splitter.classify(ast.where());
    }

    @Test
    void aHeaderComparisonRendersToItsSelectorIdentifier() {
        assertThat(selectorFor("priority > 4")).isEqualTo("JMSPriority > 4");
        assertThat(selectorFor("correlationId = 'abc'")).isEqualTo("JMSCorrelationID = 'abc'");
        assertThat(selectorFor("size >= 1024")).isEqualTo("AMQSize >= 1024");
    }

    @Test
    void aQuoteInALiteralIsDoubledNotEscaped() {
        // JMS has no backslash escape; doubling is the only way, and getting it wrong
        // is how a queue name with an apostrophe changes what a selector means.
        assertThat(selectorFor("props.tenant = 'O''Brien'")).isEqualTo("tenant = 'O''Brien'");
    }

    @Test
    void durabilityIsMappedToArtemisSpelling() {
        assertThat(selectorFor("durable = true")).isEqualTo("AMQDurable = 'DURABLE'");
        assertThat(selectorFor("durable = false")).isEqualTo("AMQDurable = 'NON_DURABLE'");
    }

    @Test
    void anOrderingComparisonOnDurabilityIsNotPushedDown() {
        // There is no ordering of DURABLE and NON_DURABLE. Rendering one would be a
        // guess, so the predicate is demoted to a scan and still applied.
        assertThat(classify("durable > false")).isEqualTo(Evaluation.SCAN);
    }

    @Test
    void inAndBetweenAndIsNullRender() {
        assertThat(selectorFor("props.t IN ('a','b')")).isEqualTo("t IN ('a', 'b')");
        assertThat(selectorFor("priority BETWEEN 1 AND 4")).isEqualTo("JMSPriority BETWEEN 1 AND 4");
        assertThat(selectorFor("correlationId IS NOT NULL")).isEqualTo("JMSCorrelationID IS NOT NULL");
    }

    @Test
    void aRelativeWindowIsResolvedAgainstTheBrokerClock() {
        // now() is the broker's now, not Studio's (ADR-0053).
        assertThat(selectorFor("timestamp > now() - interval '1 second'")).isEqualTo("JMSTimestamp > 999000");
    }

    @Test
    void aBodyPredicateNeverReachesTheSelector() {
        assertThat(classify("body LIKE '%x%'")).isEqualTo(Evaluation.SCAN);
        assertThat(classify("body->>'orderId' = '1'")).isEqualTo(Evaluation.SCAN);
        assertThat(selectorFor("body LIKE '%x%'")).isNull();
    }

    @Test
    void ilikeIsAScanRatherThanAnApproximation() {
        assertThat(classify("props.t ILIKE 'a%'")).isEqualTo(Evaluation.SCAN);
        assertThat(classify("lower(props.t) = 'a'")).isEqualTo(Evaluation.SCAN);
    }

    @Test
    void aPropertyNameASelectorCannotSpellIsAScan() {
        // A selector has no quoting for identifiers, so a name with a dash cannot be
        // pushed down. Escaping it would produce a selector that means something else.
        assertThat(classify("props.\"tenant-id\" = 'a'")).isEqualTo(Evaluation.SCAN);
    }

    @Test
    void aDisjunctionWithAResidualLeafIsNotPartiallyPushedDown() {
        // The silent-wrong-answer case: pushing down priority > 4 would hide the
        // messages the body clause was supposed to find.
        QueryAst ast = parser.parse("SELECT * FROM \"q\" WHERE priority > 4 OR body LIKE '%x%'");
        PredicateSplitter.Split split = splitter.split(ast.where());

        assertThat(split.pushdown()).isNull();
        assertThat(split.scan()).isNotNull();
        assertThat(split.requiresScan()).isTrue();
    }

    @Test
    void aConjunctionSplitsPerSideSoTheCheapHalfStillPushesDown() {
        QueryAst ast = parser.parse("SELECT * FROM \"q\" WHERE priority > 4 AND body LIKE '%x%'");
        PredicateSplitter.Split split = splitter.split(ast.where());

        assertThat(renderer.render(split.pushdown(), Instant.EPOCH)).isEqualTo("JMSPriority > 4");
        assertThat(split.requiresScan()).isTrue();
    }

    @Test
    void aQueueOrNodePredicateFiltersTargetsRatherThanMessages() {
        QueryAst ast = parser.parse("SELECT * FROM \"ORDER.*\" WHERE node = 'broker-1' AND priority > 4");
        PredicateSplitter.Split split = splitter.split(ast.where());

        assertThat(split.target()).isNotNull();
        assertThat(renderer.render(split.pushdown(), Instant.EPOCH)).isEqualTo("JMSPriority > 4");
        assertThat(split.requiresScan()).isFalse();
    }
}
