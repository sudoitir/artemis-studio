package io.github.sudoitir.artemisstudio.kernel.core;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

/**
 * Where a keyset-paged history read left off: the sort key of the last row it returned, as an opaque
 * string. The next page is the rows after that key, found by the index and not by counting past an offset,
 * so a page deep in the history costs what the first one does.
 *
 * @param major the first sort key, such as a sequence number or a timestamp in microseconds
 * @param minor the tie-breaker; zero where the first key is unique
 */
public record Cursor(long major, long minor) {

    /** A cursor for a row ordered by time and then id; microseconds, as the database keeps them. */
    public static Cursor of(Instant at, long id) {
        return new Cursor(ChronoUnit.MICROS.between(Instant.EPOCH, at), id);
    }

    /** The time of a cursor made with {@link #of(Instant, long)}. */
    public Instant instant() {
        return Instant.EPOCH.plus(major, ChronoUnit.MICROS);
    }

    public String encode() {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString((major + ":" + minor).getBytes(StandardCharsets.UTF_8));
    }

    /** The cursor {@code text} names, or {@code null} for no cursor. */
    public static Cursor decode(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(text), StandardCharsets.UTF_8).split(":");
            if (parts.length == 2) {
                return new Cursor(Long.parseLong(parts[0]), Long.parseLong(parts[1]));
            }
        } catch (IllegalArgumentException _) {
            // Not base64, or not numbers: the same refusal as a cursor of the wrong shape.
        }
        throw new IllegalArgumentException(
                "after is not a cursor this endpoint gave; use the next of a previous page.");
    }
}
