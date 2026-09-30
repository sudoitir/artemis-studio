package io.github.sudoitir.artemisstudio.kernel.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks credential-like values in text that leaves Studio: log lines, audit parameters,
 * error responses and exports. It works on the shape of the text, so it also catches a
 * secret Studio never stored. It lives in the core module because {@link Problems} uses it
 * and the core depends on nothing.
 *
 * <p>A key is credential-like when its name <em>contains</em> one of the terms below, so
 * {@code tokenCount=5} is masked too: over-masking a number costs nothing, a missed
 * credential does.
 */
public final class SecretRedactor {

    public static final String MASK = "[redacted]";

    private static final String TERMS =
            "password|passwd|pwd|secret|token|api[-_]?key|authorization|credential|private[-_]?key";

    private static final Pattern KEY = Pattern.compile("(?i).*(?:" + TERMS + ").*");

    /**
     * {@code key=value}, {@code key: value} and {@code "key":"value"}. A value is a quoted string, an
     * {@code Authorization} scheme with its token, or a run up to the next delimiter.
     */
    private static final Pattern ASSIGNMENT =
            Pattern.compile("(?i)([\\w.-]*(?:" + TERMS + ")[\\w.-]*[\"']?\\s*[=:]\\s*)"
                    + "(?!\\[redacted])(\"(?:[^\"\\\\]|\\\\.)*\"|'[^']*'|(?:(?:Bearer|Basic)\\s+)?[^\\s,;&\"'}\\]]+)");

    private static final Pattern BEARER = Pattern.compile("(?i)\\b(Bearer)\\s+(?!\\[redacted])[A-Za-z0-9._~+/=-]+");

    /** Case-sensitive and base64-shaped, so the words "Basic settings" survive. */
    private static final Pattern BASIC =
            Pattern.compile("\\b(Basic)\\s+(?=[A-Za-z0-9+/]*[0-9+/=])[A-Za-z0-9+/]{8,}={0,2}");

    private static final Pattern URL_USER_INFO = Pattern.compile("(?i)\\b([a-z][a-z0-9+.-]*://)[^\\s/?#]+@");

    private static final Pattern PEM_PRIVATE_KEY =
            Pattern.compile("(?s)-----BEGIN [A-Z ]*PRIVATE KEY-----.*?(?:-----END [A-Z ]*PRIVATE KEY-----|\\z)");

    /** {@code as_<11-char prefix>_<43-char secret>}, the format {@code ApiTokenService} issues. */
    private static final Pattern API_TOKEN = Pattern.compile("(?<![A-Za-z0-9])as_[A-Za-z0-9_-]{11}_[A-Za-z0-9_-]{43}");

    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = ASSIGNMENT.matcher(text).replaceAll(SecretRedactor::maskValue);
        out = BEARER.matcher(out).replaceAll("$1 " + MASK);
        out = BASIC.matcher(out).replaceAll("$1 " + MASK);
        out = URL_USER_INFO.matcher(out).replaceAll("$1" + Matcher.quoteReplacement(MASK) + "@");
        out = PEM_PRIVATE_KEY.matcher(out).replaceAll(Matcher.quoteReplacement(MASK));
        return API_TOKEN.matcher(out).replaceAll(Matcher.quoteReplacement(MASK));
    }

    /** Whether a parameter, header or field name suggests its value is a credential. */
    public static boolean isCredentialKey(String name) {
        return name != null && KEY.matcher(name).matches();
    }

    private static String maskValue(java.util.regex.MatchResult m) {
        String value = m.group(2);
        char quote = value.charAt(0);
        String masked = quote == '"' || quote == '\'' ? quote + MASK + quote : MASK;
        return Matcher.quoteReplacement(m.group(1) + masked);
    }

    private SecretRedactor() {}
}
