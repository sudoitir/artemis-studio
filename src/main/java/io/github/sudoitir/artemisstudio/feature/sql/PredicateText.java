package io.github.sudoitir.artemisstudio.feature.sql;

import io.github.sudoitir.artemisstudio.feature.sql.QueryAst.Literal;
import io.github.sudoitir.artemisstudio.feature.sql.QueryAst.Predicate;
import io.github.sudoitir.artemisstudio.feature.sql.QueryAst.Term;
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
            case Predicate.And(var parts) ->
                parts.stream().map(PredicateText::of).collect(Collectors.joining(" AND "));
            case Predicate.Or(var parts) ->
                parts.stream().map(PredicateText::of).collect(Collectors.joining(" OR ", "(", ")"));
            case Predicate.Not(var inner) -> "NOT " + of(inner);
            case Predicate.Compare(var term, var op, var value) -> term(term) + " " + op.sql() + " " + literal(value);
            case Predicate.In(var term, var values, var negated) ->
                term(term)
                        + (negated ? " NOT IN " : " IN ")
                        + values.stream().map(PredicateText::literal).collect(Collectors.joining(", ", "(", ")"));
            case Predicate.IsNull(var term, var negated) -> term(term) + (negated ? " IS NOT NULL" : " IS NULL");
            case Predicate.Like like ->
                term(like.term())
                        + (like.negated() ? " NOT " : " ")
                        + (like.caseInsensitive() ? "ILIKE " : "LIKE ")
                        + "'" + like.pattern() + "'";
            case Predicate.Between(var term, var low, var high, var negated) ->
                term(term) + (negated ? " NOT BETWEEN " : " BETWEEN ") + literal(low) + " AND " + literal(high);
            case Predicate.Match(var terms) -> "MATCH(body, '" + terms + "')";
        };
    }

    static String term(Term term) {
        return switch (term) {
            case Term.ColumnTerm(var column) -> column.sqlName();
            case Term.PropertyTerm(var name) -> "props." + name;
            case Term.JsonTerm(var path) -> "body->>'" + path + "'";
            case Term.CaseFold(var inner, var upper) -> (upper ? "upper(" : "lower(") + term(inner) + ")";
            case Term.MatchRank _ -> "match_rank";
        };
    }

    private static String literal(Literal literal) {
        return switch (literal) {
            case Literal.Str(var value) -> "'" + value + "'";
            case Literal.Num(var value, var integral) ->
                integral ? Long.toString((long) value) : Double.toString(value);
            case Literal.Bool(var value) -> Boolean.toString(value);
            case Literal.RelativeTime(var before) ->
                before.isZero() ? "now()" : "now() - interval '" + before.toString() + "'";
        };
    }
}
