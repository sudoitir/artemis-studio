package io.github.sudoitir.artemisstudio.feature.sql;

/**
 * A query that filters or orders on a field the content policy masks, from a caller without clear
 * access (sql-console spec). Refused before execution: a predicate is an oracle, so evaluating it
 * would reveal the value the policy hides.
 */
public class GovernanceRefusedException extends RuntimeException {

    private final String field;

    public GovernanceRefusedException(String field) {
        super("This query filters on " + field + ", which the content policy masks. Filtering on a masked field"
                + " needs the message:clear permission on this cluster; remove that predicate, or ask for the"
                + " permission.");
        this.field = field;
    }

    public String field() {
        return field;
    }
}
