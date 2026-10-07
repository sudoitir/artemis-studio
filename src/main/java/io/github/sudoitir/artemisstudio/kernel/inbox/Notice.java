package io.github.sudoitir.artemisstudio.kernel.inbox;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One notice to post to users' inboxes. The limits here are also enforced by the table, so a notice
 * that passes can always be stored.
 *
 * @param kind what the notice is about, for filtering and icons: lower-case letters, digits, dots and dashes
 * @param severity how loudly the console shows it
 * @param title one line, at most {@value #MAX_TITLE} characters
 * @param body optional detail, at most {@value #MAX_BODY} characters; plain text, never markup
 * @param link optional path inside Studio that the notice opens, such as {@code /approvals/3}; an external
 *     URL, a protocol-relative one and anything with a backslash are refused
 * @param dedupeKey optional; a second notice with the same key from the same source to the same user
 *     replaces the first and is unread again
 * @param data optional small string facts for the console; at most {@value #MAX_DATA_BYTES} bytes as JSON
 * @param ttl optional lifetime; the notice is deleted when it passes, whatever the retention
 */
@PluginApi
public record Notice(
        String kind,
        Severity severity,
        String title,
        String body,
        String link,
        String dedupeKey,
        Map<String, String> data,
        Duration ttl) {

    public static final int MAX_TITLE = 200;
    public static final int MAX_BODY = 2000;
    public static final int MAX_DATA_BYTES = 4096;

    private static final int MAX_LINK = 500;
    private static final int MAX_DEDUPE_KEY = 200;
    private static final Pattern KIND = Pattern.compile("[a-z0-9][a-z0-9.-]{0,63}");
    private static final Pattern LINK = Pattern.compile("/[A-Za-z0-9][^\\\\\\p{Cntrl}]*");

    /** How loudly the console shows a notice. */
    public enum Severity {
        INFO,
        SUCCESS,
        WARNING,
        DANGER;

        /** The value stored and sent on the wire. */
        public String wire() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public Notice {
        if (kind == null || !KIND.matcher(kind).matches()) {
            throw new IllegalArgumentException(
                    "A notice's kind must be lower-case letters, digits, dots and dashes, up to 64 characters.");
        }
        if (severity == null) {
            throw new IllegalArgumentException("A notice needs a severity.");
        }
        if (title == null || title.isBlank() || title.length() > MAX_TITLE) {
            throw new IllegalArgumentException("A notice's title must be 1 to " + MAX_TITLE + " characters.");
        }
        if (body != null && body.length() > MAX_BODY) {
            throw new IllegalArgumentException("A notice's body is longer than " + MAX_BODY + " characters.");
        }
        if (link != null && (link.length() > MAX_LINK || !LINK.matcher(link).matches() || link.contains("//"))) {
            throw new IllegalArgumentException(
                    "A notice's link must be a path inside Studio, such as /approvals/3, up to " + MAX_LINK
                            + " characters.");
        }
        if (dedupeKey != null && (dedupeKey.isEmpty() || dedupeKey.length() > MAX_DEDUPE_KEY)) {
            throw new IllegalArgumentException("A notice's dedupe key must be 1 to " + MAX_DEDUPE_KEY + " characters.");
        }
        if (ttl != null && (ttl.isNegative() || ttl.isZero())) {
            throw new IllegalArgumentException("A notice's ttl must be positive.");
        }
        data = data == null ? null : Map.copyOf(data);
    }
}
