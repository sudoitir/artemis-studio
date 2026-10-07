package io.github.sudoitir.artemisstudio.feature.alerting;

import static io.github.sudoitir.artemisstudio.feature.alerting.AlertMessageFormatter.escape;
import static io.github.sudoitir.artemisstudio.feature.alerting.AlertMessageFormatter.severityWord;
import static io.github.sudoitir.artemisstudio.feature.alerting.AlertMessageFormatter.singleLine;

import io.github.sudoitir.artemisstudio.feature.alerting.NoticeMessage.Fact;

/** What a notice says, once, for every channel kind (the notice counterpart of {@link AlertMessageFormatter}). */
final class NoticeFormatter {

    private NoticeFormatter() {}

    /** {@code [WARNING] Approval requested}. One line, no CR/LF. */
    static String headline(NoticePayload n) {
        return singleLine("[" + severityWord(n.severity()) + "] " + n.title());
    }

    static String plainText(NoticePayload n) {
        StringBuilder sb = new StringBuilder(singleLine(n.title())).append("\n\n");
        if (n.summary() != null && !n.summary().isBlank()) {
            sb.append(n.summary()).append("\n\n");
        }
        sb.append("Severity: ").append(severityWord(n.severity())).append('\n');
        for (Fact f : n.facts()) {
            sb.append(singleLine(f.label())).append(": ").append(f.value()).append('\n');
        }
        if (n.url() != null) {
            sb.append("\nOpen in Studio: ").append(n.url()).append('\n');
        }
        return sb.toString();
    }

    /** A small, self-contained HTML body. Every value is escaped. */
    static String html(NoticePayload n) {
        StringBuilder sb = new StringBuilder("<!DOCTYPE html><html><body style=\"font-family:sans-serif\">");
        sb.append("<h2 style=\"margin:0 0 8px\">")
                .append(escape(singleLine(n.title())))
                .append("</h2>");
        if (n.summary() != null && !n.summary().isBlank()) {
            sb.append("<p>").append(escape(n.summary())).append("</p>");
        }
        sb.append("<table cellpadding=\"4\" style=\"border-collapse:collapse\">");
        row(sb, "Severity", severityWord(n.severity()));
        for (Fact f : n.facts()) {
            row(sb, f.label(), f.value());
        }
        sb.append("</table>");
        if (n.url() != null) {
            sb.append("<p><a href=\"").append(escape(n.url())).append("\">Open in Studio</a></p>");
        }
        return sb.append("</body></html>").toString();
    }

    private static void row(StringBuilder sb, String label, String value) {
        sb.append("<tr><th align=\"left\">")
                .append(escape(label))
                .append("</th><td>")
                .append(escape(value))
                .append("</td></tr>");
    }
}
