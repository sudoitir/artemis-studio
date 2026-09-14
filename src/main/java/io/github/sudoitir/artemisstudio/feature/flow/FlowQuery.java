package io.github.sudoitir.artemisstudio.feature.flow;

import java.util.Locale;

/**
 * What a flow read asks for: an optional focus, how far it reaches, how paths are ranked and
 * bounded, and how clients are grouped. Every value is clamped here, so a caller cannot ask the
 * server for an unbounded graph.
 *
 * @param clamped the caller asked for more paths than {@link #MAX_LIMIT}
 */
public record FlowQuery(Focus focus, int hops, Rank rank, int limit, GroupBy groupBy, boolean clamped) {

    public static final int DEFAULT_LIMIT = 40;
    public static final int MAX_LIMIT = 200;
    public static final int MAX_HOPS = 3;

    public enum Rank {
        /** Messages added to the path's queue per second. */
        IN,
        /** Messages acknowledged from the path's queue per second. */
        OUT,
        /** Messages waiting on the path's queue. */
        BACKLOG
    }

    public enum GroupBy {
        /** Client id, falling back to user, then host. */
        CLIENT_ID,
        /** Authenticated user, falling back to host. */
        USER,
        /** Remote host without its port. */
        HOST
    }

    public enum FocusKind {
        CLIENT,
        ADDRESS,
        QUEUE
    }

    public record Focus(FocusKind kind, String name) {}

    /**
     * @param focus {@code client:<name>}, {@code address:<name>} or {@code queue:<name>}, or blank for none
     * @throws IllegalArgumentException for a focus that is not one of those shapes
     */
    public static FlowQuery of(String focus, int hops, Rank rank, int limit, GroupBy groupBy) {
        return new FlowQuery(
                parseFocus(focus),
                Math.clamp(hops, 1, MAX_HOPS),
                rank == null ? Rank.IN : rank,
                Math.clamp(limit, 1, MAX_LIMIT),
                groupBy == null ? GroupBy.CLIENT_ID : groupBy,
                limit > MAX_LIMIT);
    }

    private static Focus parseFocus(String focus) {
        if (focus == null || focus.isBlank()) {
            return null;
        }
        int colon = focus.indexOf(':');
        String message = "focus must be client:<name>, address:<name> or queue:<name>, not '" + focus + "'";
        if (colon <= 0 || colon == focus.length() - 1) {
            throw new IllegalArgumentException(message);
        }
        try {
            return new Focus(
                    FocusKind.valueOf(focus.substring(0, colon).toUpperCase(Locale.ROOT)), focus.substring(colon + 1));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(message, e);
        }
    }
}
