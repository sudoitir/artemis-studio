package io.github.sudoitir.artemisstudio.sql;

/**
 * A query that is not in the dialect (ADR-0058 D2). Every rejection names the
 * offending construct, because a rejection is a routine event an operator reads
 * and acts on, not an error condition.
 *
 * <p>{@code suggestion} carries a near match from the {@link ColumnCatalogue} when
 * there is one, so a typo costs one keystroke rather than a trip to the help.
 */
public class SqlSyntaxException extends RuntimeException {

    private final String offending;
    private final String suggestion;

    public SqlSyntaxException(String message) {
        this(message, null, null);
    }

    public SqlSyntaxException(String message, String offending) {
        this(message, offending, null);
    }

    public SqlSyntaxException(String message, String offending, String suggestion) {
        super(message);
        this.offending = offending;
        this.suggestion = suggestion;
    }

    public String offending() {
        return offending;
    }

    public String suggestion() {
        return suggestion;
    }
}
