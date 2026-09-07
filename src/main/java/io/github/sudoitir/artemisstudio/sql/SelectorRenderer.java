package io.github.sudoitir.artemisstudio.sql;

import io.github.sudoitir.artemisstudio.sql.ColumnCatalogue.Column;
import io.github.sudoitir.artemisstudio.sql.QueryAst.Literal;
import io.github.sudoitir.artemisstudio.sql.QueryAst.Predicate;
import io.github.sudoitir.artemisstudio.sql.QueryAst.Term;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Renders the pushdown half of a split {@code WHERE} clause into a JMS selector
 * (ADR-0058 D5).
 *
 * <p>This is the boundary where Studio's own text reaches the broker, so nothing the
 * operator typed passes through it verbatim. Identifiers come from the
 * {@link ColumnCatalogue} or, for an application property, from a name that has been
 * checked against the identifier grammar; string literals are escaped by doubling
 * {@code '} as the JMS spec requires. A malformed render is caught again by Artemis
 * ({@code AMQ229020}, already mapped to a 400), so a bug here fails loudly rather
 * than silently changing what a query means.
 *
 * <p>{@code now()} is resolved before rendering: {@link #render(Predicate, Instant)}
 * takes the broker-time "now" for the node being queried (ADR-0053), so a relative
 * window is expressed in the clock the broker stamped its messages with.
 */
@Component
public class SelectorRenderer {

    /**
     * A JMS selector identifier. An application property name outside this shape
     * cannot be selected on and falls back to a scan rather than being escaped into
     * the selector, because a selector has no quoting for identifiers.
     */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    /** Artemis' own selector spelling for the durability flag. */
    private static final String DURABLE_TRUE = "'DURABLE'";

    private static final String DURABLE_FALSE = "'NON_DURABLE'";

    /**
     * Whether this predicate can be rendered at all. {@link PredicateSplitter} asks
     * before classifying anything as pushdown, so a predicate that is eligible in
     * principle but unrenderable in fact — a property name a selector cannot spell,
     * an ordering comparison on the durability flag — becomes an honest scan instead
     * of a lost result.
     */
    public boolean isRenderable(Predicate predicate) {
        try {
            render(predicate, Instant.EPOCH);
            return true;
        } catch (UnrenderableSelectorException e) {
            return false;
        }
    }

    /** Thrown internally when a pushdown-classified predicate turns out not to be renderable. */
    public static class UnrenderableSelectorException extends RuntimeException {
        UnrenderableSelectorException(String message) {
            super(message);
        }
    }

    public String render(Predicate predicate, Instant now) {
        if (predicate == null) {
            return null;
        }
        String rendered = expression(predicate, now);
        return rendered.isBlank() ? null : rendered;
    }

    private String expression(Predicate predicate, Instant now) {
        return switch (predicate) {
            case Predicate.And and -> join(and.parts(), " AND ", now);
            case Predicate.Or or -> join(or.parts(), " OR ", now);
            case Predicate.Not not -> "(NOT " + expression(not.inner(), now) + ")";
            case Predicate.Compare compare -> compare(compare, now);
            case Predicate.In in -> in(in, now);
            case Predicate.IsNull isNull ->
                identifier(isNull.term()) + (isNull.negated() ? " IS NOT NULL" : " IS NULL");
            case Predicate.Like like -> like(like);
            case Predicate.Between between -> between(between, now);
        };
    }

    private String join(List<Predicate> parts, String operator, Instant now) {
        return parts.stream().map(p -> expression(p, now)).collect(Collectors.joining(operator, "(", ")"));
    }

    private String compare(Predicate.Compare compare, Instant now) {
        if (isDurable(compare.term())) {
            return durable(compare, now);
        }
        return identifier(compare.term()) + " " + compare.op().sql() + " " + literal(compare.value(), now);
    }

    /**
     * Artemis expresses durability as a string, not a boolean, so {@code durable =
     * true} becomes {@code AMQDurable = 'DURABLE'}. That is an exact mapping of two
     * values, not an approximation — which is why it is allowed to be pushed down at
     * all. Any other operator on it would not be.
     */
    private String durable(Predicate.Compare compare, Instant now) {
        if (!(compare.value() instanceof Literal.Bool bool)) {
            throw new UnrenderableSelectorException("durable compares against true or false");
        }
        String value = bool.value() ? DURABLE_TRUE : DURABLE_FALSE;
        return switch (compare.op()) {
            case EQ -> ColumnCatalogue.Column.DURABLE.selectorId() + " = " + value;
            case NE -> ColumnCatalogue.Column.DURABLE.selectorId() + " <> " + value;
            default -> throw new UnrenderableSelectorException("durable is only comparable with = or <>");
        };
    }

    private boolean isDurable(Term term) {
        return term instanceof Term.ColumnTerm column && column.column() == Column.DURABLE;
    }

    private String in(Predicate.In in, Instant now) {
        String values = in.values().stream().map(v -> literal(v, now)).collect(Collectors.joining(", ", "(", ")"));
        return identifier(in.term()) + (in.negated() ? " NOT IN " : " IN ") + values;
    }

    private String like(Predicate.Like like) {
        if (like.caseInsensitive()) {
            // Never reached through the splitter, which classifies ILIKE as a scan.
            // Guarded here too so a future caller cannot quietly get an approximation.
            throw new UnrenderableSelectorException("a selector has no case-insensitive LIKE");
        }
        String escape = like.escape() == null ? "" : " ESCAPE " + quote(String.valueOf(like.escape()));
        return identifier(like.term()) + (like.negated() ? " NOT LIKE " : " LIKE ") + quote(like.pattern()) + escape;
    }

    private String between(Predicate.Between between, Instant now) {
        String id = identifier(between.term());
        String range = literal(between.low(), now) + " AND " + literal(between.high(), now);
        return id + (between.negated() ? " NOT BETWEEN " : " BETWEEN ") + range;
    }

    // ---- identifiers and literals --------------------------------------

    private String identifier(Term term) {
        return switch (term) {
            case Term.ColumnTerm column -> {
                String selectorId = column.column().selectorId();
                if (selectorId == null) {
                    throw new UnrenderableSelectorException(
                            "no selector identifier for " + column.column().sqlName());
                }
                yield selectorId;
            }
            case Term.PropertyTerm property -> {
                if (!IDENTIFIER.matcher(property.name()).matches()) {
                    throw new UnrenderableSelectorException(
                            "a selector cannot quote the property name '" + property.name() + "'");
                }
                yield property.name();
            }
            case Term.JsonTerm ignored -> throw new UnrenderableSelectorException("a selector cannot read the body");
            case Term.CaseFold ignored -> throw new UnrenderableSelectorException("a selector cannot fold case");
        };
    }

    private String literal(Literal literal, Instant now) {
        return switch (literal) {
            case Literal.Str str -> quote(str.value());
            case Literal.Num num -> num.integral() ? Long.toString((long) num.value()) : Double.toString(num.value());
            case Literal.Bool bool -> Boolean.toString(bool.value()).toUpperCase(java.util.Locale.ROOT);
            case Literal.RelativeTime relative ->
                Long.toString(now.minus(relative.before()).toEpochMilli());
        };
    }

    /** JMS string literals escape a quote by doubling it. There is no backslash escape. */
    private String quote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
