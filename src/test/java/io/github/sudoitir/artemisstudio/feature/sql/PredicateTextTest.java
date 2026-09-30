package io.github.sudoitir.artemisstudio.feature.sql;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.feature.sql.ColumnCatalogue.Column;
import io.github.sudoitir.artemisstudio.feature.sql.QueryAst.Literal;
import io.github.sudoitir.artemisstudio.feature.sql.QueryAst.Operator;
import io.github.sudoitir.artemisstudio.feature.sql.QueryAst.Predicate;
import io.github.sudoitir.artemisstudio.feature.sql.QueryAst.Term;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The EXPLAIN strip prints the AST back in the dialect the operator wrote. */
class PredicateTextTest {

    private static final Term QUEUE = new Term.ColumnTerm(Column.QUEUE);

    @Test
    void comparesRenderEachOperatorAndLiteralKind() {
        assertThat(PredicateText.of(new Predicate.Compare(QUEUE, Operator.EQ, new Literal.Str("orders"))))
                .isEqualTo("queue = 'orders'");
        assertThat(PredicateText.of(new Predicate.Compare(QUEUE, Operator.GTE, new Literal.Num(5, true))))
                .isEqualTo("queue >= 5");
        assertThat(PredicateText.of(new Predicate.Compare(QUEUE, Operator.LT, new Literal.Num(2.5, false))))
                .isEqualTo("queue < 2.5");
        assertThat(PredicateText.of(new Predicate.Compare(QUEUE, Operator.NE, new Literal.Bool(true))))
                .isEqualTo("queue <> true");
    }

    @Test
    void relativeTimeIsNowOrNowMinusAnInterval() {
        assertThat(PredicateText.of(new Predicate.Compare(QUEUE, Operator.LT, new Literal.RelativeTime(Duration.ZERO))))
                .isEqualTo("queue < now()");
        assertThat(PredicateText.of(
                        new Predicate.Compare(QUEUE, Operator.GT, new Literal.RelativeTime(Duration.ofHours(2)))))
                .isEqualTo("queue > now() - interval 'PT2H'");
    }

    @Test
    void andJoinsBareAndOrIsParenthesisedAndNotPrefixes() {
        Predicate a = new Predicate.Compare(QUEUE, Operator.EQ, new Literal.Str("a"));
        Predicate b = new Predicate.IsNull(QUEUE, false);

        assertThat(PredicateText.of(new Predicate.And(List.of(a, b)))).isEqualTo("queue = 'a' AND queue IS NULL");
        assertThat(PredicateText.of(new Predicate.Or(List.of(a, b)))).isEqualTo("(queue = 'a' OR queue IS NULL)");
        assertThat(PredicateText.of(new Predicate.Not(a))).isEqualTo("NOT queue = 'a'");
    }

    @Test
    void inAndIsNullAndBetweenRenderBothPolarities() {
        List<Literal> values = List.of(new Literal.Str("a"), new Literal.Num(1, true));
        assertThat(PredicateText.of(new Predicate.In(QUEUE, values, false))).isEqualTo("queue IN ('a', 1)");
        assertThat(PredicateText.of(new Predicate.In(QUEUE, values, true))).isEqualTo("queue NOT IN ('a', 1)");

        assertThat(PredicateText.of(new Predicate.IsNull(QUEUE, false))).isEqualTo("queue IS NULL");
        assertThat(PredicateText.of(new Predicate.IsNull(QUEUE, true))).isEqualTo("queue IS NOT NULL");

        Literal low = new Literal.Num(1, true);
        Literal high = new Literal.Num(9, true);
        assertThat(PredicateText.of(new Predicate.Between(QUEUE, low, high, false)))
                .isEqualTo("queue BETWEEN 1 AND 9");
        assertThat(PredicateText.of(new Predicate.Between(QUEUE, low, high, true)))
                .isEqualTo("queue NOT BETWEEN 1 AND 9");
    }

    @Test
    void likeRendersCaseSensitivityAndNegation() {
        assertThat(PredicateText.of(new Predicate.Like(QUEUE, "a%", null, false, false)))
                .isEqualTo("queue LIKE 'a%'");
        assertThat(PredicateText.of(new Predicate.Like(QUEUE, "a%", null, false, true)))
                .isEqualTo("queue ILIKE 'a%'");
        assertThat(PredicateText.of(new Predicate.Like(QUEUE, "a%", null, true, false)))
                .isEqualTo("queue NOT LIKE 'a%'");
        assertThat(PredicateText.of(new Predicate.Like(QUEUE, "a%", null, true, true)))
                .isEqualTo("queue NOT ILIKE 'a%'");
    }

    @Test
    void matchRendersTheSearchTerms() {
        assertThat(PredicateText.of(new Predicate.Match("\"exact phrase\" -no")))
                .isEqualTo("MATCH(body, '\"exact phrase\" -no')");
    }

    @Test
    void termsRenderColumnsPropertiesJsonPathsCaseFoldsAndRank() {
        assertThat(PredicateText.term(QUEUE)).isEqualTo("queue");
        assertThat(PredicateText.term(new Term.PropertyTerm("tenant"))).isEqualTo("props.tenant");
        assertThat(PredicateText.term(new Term.JsonTerm("a.b"))).isEqualTo("body->>'a.b'");
        assertThat(PredicateText.term(new Term.CaseFold(QUEUE, true))).isEqualTo("upper(queue)");
        assertThat(PredicateText.term(new Term.CaseFold(new Term.PropertyTerm("x"), false)))
                .isEqualTo("lower(props.x)");
        assertThat(PredicateText.term(new Term.MatchRank())).isEqualTo("match_rank");
    }
}
