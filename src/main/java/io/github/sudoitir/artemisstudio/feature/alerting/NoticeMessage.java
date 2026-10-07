package io.github.sudoitir.artemisstudio.feature.alerting;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import java.util.List;
import java.util.regex.Pattern;

/**
 * One notice a plugin sends to a notification channel. Every channel kind renders it: the title as the
 * headline, the summary and the facts as the body, and a link back into Studio when there is one.
 *
 * @param title one line, at most {@value #MAX_TITLE} characters
 * @param summary optional detail, at most {@value #MAX_SUMMARY} characters; plain text, never markup
 * @param severity how loudly the receiver shows it
 * @param facts optional label and value pairs, at most {@value #MAX_FACTS}
 * @param url optional path inside Studio that the notice opens, such as {@code /approvals/3}; Studio makes it
 *     absolute from its public address, and sends no link when it has none. An external URL, a
 *     protocol-relative one and anything with a backslash are refused
 */
@PluginApi
public record NoticeMessage(String title, String summary, Severity severity, List<Fact> facts, String url) {

    public static final int MAX_TITLE = 200;
    public static final int MAX_SUMMARY = 2000;
    public static final int MAX_FACTS = 10;
    public static final int MAX_LABEL = 100;
    public static final int MAX_VALUE = 500;

    private static final int MAX_URL = 500;
    private static final Pattern URL = Pattern.compile("/[A-Za-z0-9][^\\\\\\p{Cntrl}]*");

    /** How loudly a receiver shows a notice. */
    public enum Severity {
        INFO,
        WARNING,
        CRITICAL
    }

    /** One labelled value, such as {@code Requested by} and {@code alice}. */
    @PluginApi
    public record Fact(String label, String value) {

        public Fact {
            if (label == null || label.isBlank() || label.length() > MAX_LABEL) {
                throw new IllegalArgumentException("A fact's label must be 1 to " + MAX_LABEL + " characters.");
            }
            if (value == null || value.length() > MAX_VALUE) {
                throw new IllegalArgumentException("A fact's value must be at most " + MAX_VALUE + " characters.");
            }
        }
    }

    public NoticeMessage {
        if (title == null || title.isBlank() || title.length() > MAX_TITLE) {
            throw new IllegalArgumentException("A notice's title must be 1 to " + MAX_TITLE + " characters.");
        }
        if (summary != null && summary.length() > MAX_SUMMARY) {
            throw new IllegalArgumentException("A notice's summary is longer than " + MAX_SUMMARY + " characters.");
        }
        if (severity == null) {
            throw new IllegalArgumentException("A notice needs a severity.");
        }
        facts = facts == null ? List.of() : List.copyOf(facts);
        if (facts.size() > MAX_FACTS) {
            throw new IllegalArgumentException("A notice has at most " + MAX_FACTS + " facts.");
        }
        if (url != null && (url.length() > MAX_URL || !URL.matcher(url).matches() || url.contains("//"))) {
            throw new IllegalArgumentException(
                    "A notice's url must be a path inside Studio, such as /approvals/3, up to " + MAX_URL
                            + " characters.");
        }
    }
}
