package io.github.sudoitir.artemisstudio.platform.broker;

import java.time.Instant;

/**
 * A message filter frozen at a moment (ADR-0097): only messages whose producer timestamp is at or
 * before {@code t0} match, so a selection over a queue that is still being produced to has an end.
 */
public final class FrozenFilter {

    private FrozenFilter() {}

    /**
     * {@code (<filter>) AND AMQTimestamp <= <t0 millis>}, or the timestamp clause alone when there
     * is no filter. The filter is always parenthesised, so an {@code OR} in it cannot escape the
     * clause.
     */
    public static String compose(String filter, Instant t0) {
        String frozen = "AMQTimestamp <= " + t0.toEpochMilli();
        return filter == null || filter.isBlank() ? frozen : "(" + filter.strip() + ") AND " + frozen;
    }
}
