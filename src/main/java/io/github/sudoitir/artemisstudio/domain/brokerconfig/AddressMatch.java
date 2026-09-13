package io.github.sudoitir.artemisstudio.domain.brokerconfig;

import java.util.Locale;

/**
 * Artemis' default wildcard syntax for a {@code match} pattern: words separated by
 * {@code .}, {@code *} standing for exactly one word and {@code #} for any number of
 * words including none. Used to answer "does this match cover that address" when
 * classifying hazards — never to resolve settings, which is the broker's job.
 */
public final class AddressMatch {

    private AddressMatch() {}

    /** Whether {@code pattern} covers {@code address} under the default wildcard configuration. */
    public static boolean covers(String pattern, String address) {
        if (pattern == null || address == null) {
            return false;
        }
        if (pattern.equals(address)) {
            return true;
        }
        return matches(pattern.split("\\.", -1), 0, address.split("\\.", -1), 0);
    }

    private static boolean matches(String[] p, int pi, String[] a, int ai) {
        if (pi == p.length) {
            return ai == a.length;
        }
        String word = p[pi];
        if (word.equals("#")) {
            for (int skip = ai; skip <= a.length; skip++) {
                if (matches(p, pi + 1, a, skip)) {
                    return true;
                }
            }
            return false;
        }
        if (ai == a.length) {
            return false;
        }
        if (word.equals("*") || word.equals(a[ai])) {
            return matches(p, pi + 1, a, ai + 1);
        }
        return false;
    }

    /** {@code #}, {@code *} and their dotted spellings cover every address on a broker. */
    public static boolean isCatchAll(String pattern) {
        if (pattern == null) {
            return false;
        }
        String p = pattern.trim().toLowerCase(Locale.ROOT);
        return p.equals("#") || p.equals("*") || p.equals("#.#");
    }
}
