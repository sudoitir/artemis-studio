package io.github.sudoitir.artemisstudio.feature.flow;

/**
 * Artemis' default address wildcard syntax (flow-visualization spec): words are delimited by
 * {@code .}, {@code *} matches exactly one word and {@code #} matches zero or more. A broker can
 * redefine these characters in {@code broker.xml}; that is not readable over management, so the
 * view states the assumption instead of guessing.
 */
final class WildcardMatcher {

    private WildcardMatcher() {}

    static boolean isWildcard(String address) {
        return address != null && (address.contains("#") || address.contains("*"));
    }

    static boolean matches(String pattern, String address) {
        if (pattern == null || address == null) {
            return false;
        }
        return match(pattern.split("\\.", -1), 0, address.split("\\.", -1), 0);
    }

    // ponytail: backtracking on '#', exponential only for patterns with several '#'; addresses carry
    // one at most in practice. Memoise on (i, j) if a pathological pattern ever shows up.
    private static boolean match(String[] pattern, int i, String[] words, int j) {
        if (i == pattern.length) {
            return j == words.length;
        }
        if (pattern[i].equals("#")) {
            for (int k = j; k <= words.length; k++) {
                if (match(pattern, i + 1, words, k)) {
                    return true;
                }
            }
            return false;
        }
        if (j == words.length) {
            return false;
        }
        return (pattern[i].equals("*") || pattern[i].equals(words[j])) && match(pattern, i + 1, words, j + 1);
    }
}
