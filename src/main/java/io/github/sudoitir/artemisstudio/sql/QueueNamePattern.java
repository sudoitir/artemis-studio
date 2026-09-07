package io.github.sudoitir.artemisstudio.sql;

import java.util.regex.Pattern;

/**
 * Matches a {@code FROM} target against real queue names using <em>Artemis'</em>
 * wildcard syntax, not SQL's — {@code *} is exactly one dot-delimited word and
 * {@code #} is any number of them. An operator who has written {@code broker.xml}
 * address settings already knows this syntax, and borrowing SQL's {@code %} here
 * would leave two wildcard dialects in one product.
 *
 * <p>Public because retention has to answer the same question: which real queues an
 * index subscription's pattern claims. One matcher, or the index expires rows a
 * query would still have expected to find.
 */
public final class QueueNamePattern {

    private QueueNamePattern() {}

    static boolean isPattern(String pattern) {
        return pattern.indexOf('*') >= 0 || pattern.indexOf('#') >= 0;
    }

    static Pattern compile(String pattern) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            switch (c) {
                case '*' -> regex.append("[^.]*");
                case '#' -> regex.append(".*");
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString());
    }

    public static boolean matches(String pattern, String queueName) {
        if (!isPattern(pattern)) {
            return pattern.equals(queueName);
        }
        return compile(pattern).matcher(queueName).matches();
    }
}
