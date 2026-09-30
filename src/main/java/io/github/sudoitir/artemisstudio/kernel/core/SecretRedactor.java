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

    private static final Pattern KEY = Pattern.compile("(?i)" + TERMS);

    /**
     * {@code key=value}, {@code key: value} and {@code "key":"value"}. A value is a quoted string, an
     * {@code Authorization} scheme with its token, or a run up to the next delimiter. The key is bounded and
     * possessive and every run is possessive, so no input makes the match super-linear; {@link #isCredentialKey}
     * decides afterwards whether the key is one. A key longer than the bound is judged on its last characters.
     */
    private static final Pattern ASSIGNMENT = Pattern.compile("([\\w.-]{1,64}+)([\"']?\\s*+[=:]\\s*+)"
            + "(?!\\[redacted])(\"(?:[^\"\\\\]|\\\\.)*+\"|'[^']*+'|(?:(?:Bearer|Basic)\\s++)?[^\\s,;&\"'}\\]]++)");

    private static final Pattern BEARER = Pattern.compile("(?i)\\b(Bearer)\\s++(?!\\[redacted])[A-Za-z0-9._~+/=-]++");

    /** Case-sensitive and base64-shaped, so the words "Basic settings" survive. */
    private static final Pattern BASIC =
            Pattern.compile("\\b(Basic)\\s++(?=[A-Za-z0-9+/]*[0-9+/=])[A-Za-z0-9+/]{8,}={0,2}");

    /** The scheme and the user-info are bounded, so a long run of scheme characters cannot make it quadratic. */
    private static final Pattern URL_USER_INFO =
            Pattern.compile("(?i)((?<![a-z0-9+.-])[a-z][a-z0-9+.-]{0,31}+://)[^\\s/?#]{1,256}@");

    private static final Pattern PEM_PRIVATE_KEY = Pattern.compile(
            // Possessive body up to the END marker (or the end of the text), so repeated BEGIN markers stay linear.
            "-----BEGIN [A-Z ]{0,32}PRIVATE KEY-----(?:[^-]++|-(?!----END ))*+"
                    + "(?:-----END [A-Z ]{0,32}PRIVATE KEY-----)?");

    /** {@code as_<11-char prefix>_<43-char secret>}, the format {@code ApiTokenService} issues. */
    private static final Pattern API_TOKEN = Pattern.compile("(?<![A-Za-z0-9])as_[A-Za-z0-9_-]{11}_[A-Za-z0-9_-]{43}");

    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = maskAssignments(text);
        out = BEARER.matcher(out).replaceAll("$1 " + MASK);
        out = BASIC.matcher(out).replaceAll("$1 " + MASK);
        out = URL_USER_INFO.matcher(out).replaceAll("$1" + Matcher.quoteReplacement(MASK) + "@");
        out = PEM_PRIVATE_KEY.matcher(out).replaceAll(Matcher.quoteReplacement(MASK));
        return API_TOKEN.matcher(out).replaceAll(Matcher.quoteReplacement(MASK));
    }

    /** Whether a parameter, header or field name suggests its value is a credential. */
    public static boolean isCredentialKey(String name) {
        return name != null && KEY.matcher(name).find();
    }

    /** A value whose key is not a credential is left alone, and the scan resumes inside it: it may hold one. */
    private static String maskAssignments(String text) {
        Matcher m = ASSIGNMENT.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        int copied = 0;
        while (m.find()) {
            if (!isCredentialKey(m.group(1))) {
                m.region(m.end(2), text.length());
                continue;
            }
            String value = m.group(3);
            char quote = value.charAt(0);
            out.append(text, copied, m.start(3)).append(quote == '"' || quote == '\'' ? quote + MASK + quote : MASK);
            copied = m.end();
        }
        return out.append(text, copied, text.length()).toString();
    }

    private SecretRedactor() {}
}
