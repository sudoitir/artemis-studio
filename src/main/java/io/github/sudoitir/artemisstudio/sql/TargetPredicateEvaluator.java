package io.github.sudoitir.artemisstudio.sql;

import io.github.sudoitir.artemisstudio.sql.ColumnCatalogue.Column;
import io.github.sudoitir.artemisstudio.sql.QueryAst.Literal;
import io.github.sudoitir.artemisstudio.sql.QueryAst.Predicate;
import io.github.sudoitir.artemisstudio.sql.QueryAst.Term;
import io.github.sudoitir.artemisstudio.sql.QueryPlan.Target;

/**
 * Evaluates the target-level conjuncts — {@code queue}, {@code address},
 * {@code node} — against a resolved target, so a node the query excludes is never
 * asked at all. This is the cheapest of the three evaluation classes: it runs during
 * planning, before any broker is contacted.
 */
final class TargetPredicateEvaluator {

    private TargetPredicateEvaluator() {}

    static boolean matches(Predicate predicate, Target target) {
        return switch (predicate) {
            case Predicate.And and -> and.parts().stream().allMatch(p -> matches(p, target));
            case Predicate.Or or -> or.parts().stream().anyMatch(p -> matches(p, target));
            case Predicate.Not not -> !matches(not.inner(), target);
            case Predicate.Compare compare -> compare(compare, target);
            case Predicate.In in -> in(in, target);
            case Predicate.IsNull isNull -> (value(isNull.term(), target) == null) != isNull.negated();
            case Predicate.Like like -> like(like, target);
            case Predicate.Between ignored -> true;
            // A target is a queue on a node; nothing about it can be full-text
            // searched, so this predicate excludes no target.
            case Predicate.Match ignored -> true;
        };
    }

    private static boolean compare(Predicate.Compare compare, Target target) {
        String left = value(compare.term(), target);
        if (left == null || !(compare.value() instanceof Literal.Str right)) {
            return true;
        }
        int order = left.compareTo(right.value());
        return switch (compare.op()) {
            case EQ -> order == 0;
            case NE -> order != 0;
            case LT -> order < 0;
            case LTE -> order <= 0;
            case GT -> order > 0;
            case GTE -> order >= 0;
        };
    }

    private static boolean in(Predicate.In in, Target target) {
        String left = value(in.term(), target);
        if (left == null) {
            return true;
        }
        boolean found = in.values().stream()
                .anyMatch(v -> v instanceof Literal.Str str && str.value().equals(left));
        return found != in.negated();
    }

    private static boolean like(Predicate.Like like, Target target) {
        String left = value(like.term(), target);
        if (left == null) {
            return true;
        }
        int flags = like.caseInsensitive() ? java.util.regex.Pattern.CASE_INSENSITIVE : 0;
        boolean hit = java.util.regex.Pattern.compile(
                        MessagePredicate.likeToRegex(like.pattern(), like.escape()), flags)
                .matcher(left)
                .matches();
        return hit != like.negated();
    }

    private static String value(Term term, Target target) {
        if (!(term instanceof Term.ColumnTerm column)) {
            return null;
        }
        Column c = column.column();
        if (c == Column.QUEUE) {
            return target.queueName();
        }
        if (c == Column.ADDRESS) {
            return target.address();
        }
        if (c == Column.NODE) {
            return target.nodeName();
        }
        return null;
    }
}
