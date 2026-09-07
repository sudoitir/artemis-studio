package io.github.sudoitir.artemisstudio.sql;

/**
 * A query whose estimated cost is over the ceiling (ADR-0058 D6). It is refused
 * before the first broker call rather than started and truncated: a truncated result
 * looks exactly like a complete one, and an operator mid-incident reads it as "the
 * message is not there".
 */
public class CostRefusedException extends RuntimeException {

    private final long estimate;
    private final long ceiling;
    private final String hint;

    public CostRefusedException(long estimate, long ceiling, String hint) {
        super("This query would examine about " + estimate + " messages, over the ceiling of " + ceiling + ". " + hint);
        this.estimate = estimate;
        this.ceiling = ceiling;
        this.hint = hint;
    }

    public long estimate() {
        return estimate;
    }

    public long ceiling() {
        return ceiling;
    }

    public String hint() {
        return hint;
    }
}
