package io.github.sudoitir.artemisstudio.platform.broker;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The names a sent message may carry, checked before anything is audited or sent. The broker's JMS
 * client refuses an application property whose name is not a Java identifier, is a selector keyword
 * or claims a {@code JMS} header, and answers with an error that is the caller's, not the broker's.
 */
public final class SendNames {

    /** The headers a send accepts, in the order the error message lists them. */
    public static final List<String> HEADERS = List.of("correlationId", "type", "replyTo", "groupId", "groupSeq");

    private static final Set<String> SELECTOR_KEYWORDS =
            Set.of("NULL", "TRUE", "FALSE", "NOT", "AND", "OR", "BETWEEN", "LIKE", "IN", "IS", "ESCAPE");

    /** The only {@code JMS}-prefixed names a client may set. */
    private static final Set<String> SETTABLE_JMS = Set.of("JMSXGroupID", "JMSXGroupSeq");

    private SendNames() {}

    /** Throws {@link IllegalArgumentException}, naming the offender, for the first bad name or header value. */
    public static void validate(Map<String, Object> headers, Map<String, Object> properties) {
        for (String name : headers.keySet()) {
            if (!HEADERS.contains(name)) {
                throw new IllegalArgumentException(
                        "Unsupported header '" + name + "'. Supported headers: " + String.join(", ", HEADERS) + ".");
            }
        }
        Object seq = headers.get("groupSeq");
        if (seq != null && !(seq instanceof Integer) && !isInt(String.valueOf(seq))) {
            throw new IllegalArgumentException("Header 'groupSeq' must be an integer, got '" + seq + "'.");
        }
        properties.keySet().forEach(SendNames::validateProperty);
    }

    private static void validateProperty(String name) {
        if (!isIdentifier(name)
                || SELECTOR_KEYWORDS.contains(name.toUpperCase(java.util.Locale.ROOT))
                || (name.startsWith("JMS") && !SETTABLE_JMS.contains(name))) {
            throw new IllegalArgumentException("Invalid property name '" + name
                    + "'. A property name must be a Java identifier (letters, digits, '_' or '$', not starting"
                    + " with a digit), must not be a selector keyword (NULL, TRUE, FALSE, NOT, AND, OR, BETWEEN,"
                    + " LIKE, IN, IS, ESCAPE) and must not start with 'JMS' (except JMSXGroupID and JMSXGroupSeq).");
        }
    }

    private static boolean isIdentifier(String s) {
        if (s == null || s.isEmpty() || !Character.isJavaIdentifierStart(s.charAt(0))) {
            return false;
        }
        return s.chars().skip(1).allMatch(Character::isJavaIdentifierPart);
    }

    private static boolean isInt(String s) {
        try {
            Integer.parseInt(s);
            return true;
        } catch (NumberFormatException _) {
            return false;
        }
    }
}
