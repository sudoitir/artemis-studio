package io.github.sudoitir.artemisstudio.sql;

import io.github.sudoitir.artemisstudio.sql.QueryAst.Literal;
import io.github.sudoitir.artemisstudio.sql.QueryAst.Predicate;
import io.github.sudoitir.artemisstudio.sql.QueryAst.Term;
import java.util.stream.Collectors;

/**
 * Renders a predicate back into the dialect, for the EXPLAIN strip. The operator has
 * to be able to match "this part is pushed down" against the clause they actually
 * wrote, so this prints the {@link QueryAst} rather than slicing the original text.
 */
final class PredicateText {

    private PredicateText() {}

    static String of(Predicate predicate) {
        return switch (predicate) {
            case Predicate.And and ->
                and.parts().stream().map(PredicateText::of).collect(Collectors.joining(" AND "));
            case Predicate.Or or ->
                or.parts().stream().map(PredicateText::of).collect(Collectors.joining(" OR ", "(", ")"));
            case Predicate.Not not -> "NOT " + of(not.inner());
            case Predicate.Compare compare ->
                term(compare.term()) + " " + compare.op().sql() + " " + literal(compare.value());
            case Predicate.In in ->
                term(in.term())
                        + (in.negated() ? " NOT IN " : " IN ")
                        + in.values().stream().map(PredicateText::literal).collect(Collectors.joining(", ", "(", ")"));
            case Predicate.IsNull isNull -> term(isNull.term()) + (isNull.negated() ? " IS NOT NULL" : " IS NULL");
            case Predicate.Like like ->
                term(like.term())
                        + (like.negated() ? " NOT " : " ")
                        + (like.caseInsensitive() ? "ILIKE " : "LIKE ")
                        + "'" + like.pattern() + "'";
            case Predicate.Between between ->
                term(between.term())
                        + (between.negated() ? " NOT BETWEEN " : " BETWEEN ")
                        + literal(between.low())
                        + " AND "
                        + literal(between.high());
            case Predicate.Match match -> "MATCH(body, '" + match.terms() + "')";
        };
    }

    static String term(Term term) {
        return switch (term) {
            case Term.ColumnTerm column -> column.column().sqlName();
            case Term.PropertyTerm property -> "props." + property.name();
            case Term.JsonTerm json -> "body->>'" + json.path() + "'";
            case Term.CaseFold fold -> (fold.upper() ? "upper(" : "lower(") + term(fold.inner()) + ")";
            case Term.MatchRank ignored -> "match_rank";
        };
    }

    private static String literal(Literal literal) {
        return switch (literal) {
            case Literal.Str str -> "'" + str.value() + "'";
            case Literal.Num num -> num.integral() ? Long.toString((long) num.value()) : Double.toString(num.value());
            case Literal.Bool bool -> Boolean.toString(bool.value());
            case Literal.RelativeTime relative ->
                relative.before().isZero()
                        ? "now()"
                        : "now() - interval '" + relative.before().toString() + "'";
        };
    }
}
