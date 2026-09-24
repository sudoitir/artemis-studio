package io.github.sudoitir.artemisstudio.feature.alerting;

import io.github.sudoitir.artemisstudio.feature.alerting.AlertMessage.Line;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * What a notification says, once, for every channel kind (ADR-0105 D1) — so Slack, Teams, email
 * and PagerDuty cannot drift apart in which facts they state. Severity is always a word; colour,
 * where a channel has it, is redundant emphasis.
 */
public final class AlertMessageFormatter {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    private AlertMessageFormatter() {}

    /** {@code [CRITICAL] Node down — prod-eu: 2 firing, 1 resolved}. One line, no CR/LF. */
    public static String title(AlertMessage m) {
        StringBuilder sb = new StringBuilder()
                .append('[')
                .append(severityWord(m.severity()))
                .append("] ")
                .append(m.ruleName());
        if (m.clusterName() != null) {
            sb.append(" — ").append(m.clusterName());
        }
        sb.append(": ").append(counts(m));
        return singleLine(sb.toString());
    }

    /** {@code 2 firing, 1 resolved}, leaving out a zero. */
    public static String counts(AlertMessage m) {
        long fired = m.firedCount();
        long resolved = m.resolvedCount();
        if (fired > 0 && resolved > 0) {
            return fired + " firing, " + resolved + " resolved";
        }
        return fired > 0 ? fired + " firing" : resolved + " resolved";
    }

    /** {@code FIRING — node primary-1 (value 1) at 2026-09-24 10:00:00 UTC}. */
    public static String line(Line t) {
        StringBuilder sb = new StringBuilder(t.fired() ? "FIRING" : "RESOLVED")
                .append(" — ")
                .append(t.label());
        if (t.value() != null) {
            sb.append(" (value ").append(number(t.value())).append(')');
        }
        if (t.at() != null) {
            sb.append(" at ").append(TIME.format(t.at()));
        }
        return singleLine(sb.toString());
    }

    public static String plainText(AlertMessage m) {
        StringBuilder sb = new StringBuilder(title(m)).append("\n\n");
        sb.append("Severity: ").append(severityWord(m.severity())).append('\n');
        sb.append("Rule: ").append(m.ruleName()).append('\n');
        if (m.clusterName() != null) {
            sb.append("Cluster: ").append(m.clusterName()).append('\n');
        }
        sb.append('\n');
        for (Line t : m.transitions()) {
            sb.append("• ").append(line(t)).append('\n');
        }
        if (m.studioUrl() != null) {
            sb.append("\nOpen in Studio: ").append(m.studioUrl()).append('\n');
        }
        return sb.toString();
    }

    /** A small, self-contained HTML body. Every value is escaped. */
    public static String html(AlertMessage m) {
        StringBuilder sb = new StringBuilder("<!DOCTYPE html><html><body style=\"font-family:sans-serif\">");
        sb.append("<h2 style=\"margin:0 0 8px\">").append(escape(title(m))).append("</h2>");
        sb.append("<table cellpadding=\"4\" style=\"border-collapse:collapse\">");
        row(sb, "Severity", severityWord(m.severity()));
        row(sb, "Rule", m.ruleName());
        if (m.clusterName() != null) {
            row(sb, "Cluster", m.clusterName());
        }
        sb.append("</table><ul>");
        for (Line t : m.transitions()) {
            sb.append("<li>").append(escape(line(t))).append("</li>");
        }
        sb.append("</ul>");
        if (m.studioUrl() != null) {
            sb.append("<p><a href=\"").append(escape(m.studioUrl())).append("\">Open in Studio</a></p>");
        }
        return sb.append("</body></html>").toString();
    }

    public static String severityWord(String severity) {
        return switch (severity == null ? "" : severity) {
            case "CRITICAL" -> "CRITICAL";
            case "WARNING" -> "WARNING";
            default -> "INFO";
        };
    }

    public static String number(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }
        return String.format(Locale.ROOT, "%.3f", value);
    }

    /** Collapses CR, LF and other control characters so a value can never start a new header line. */
    public static String singleLine(String value) {
        return value == null ? "" : value.replaceAll("[\\p{Cntrl}]+", " ").trim();
    }

    public static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static void row(StringBuilder sb, String label, String value) {
        sb.append("<tr><th align=\"left\">")
                .append(escape(label))
                .append("</th><td>")
                .append(escape(value))
                .append("</td></tr>");
    }
}
