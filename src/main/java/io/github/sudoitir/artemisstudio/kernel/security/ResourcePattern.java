package io.github.sudoitir.artemisstudio.kernel.security;

import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * A queue or address name pattern in the broker's own wildcard syntax: words separated by {@code .},
 * {@code *} standing for exactly one word and {@code #} for zero or more words. Operators write the
 * same patterns in the broker's {@code security-settings}.
 *
 * <p>{@link #overlaps} and {@link #covers} are exact, not approximations: team patterns on one cluster
 * may not overlap, and a share must lie within what its owner owns.
 */
public record ResourcePattern(String text, List<String> words) {

    private static final String ONE = "*";
    private static final String MANY = "#";

    /** A word no pattern contains (a pattern has no empty word), standing for every literal not named. */
    private static final String OTHER = "";

    /** @throws IllegalArgumentException with a message naming the fault when {@code text} is not a pattern */
    public static ResourcePattern parse(String text) {
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("A pattern cannot be empty");
        }
        List<String> words = List.of(text.split("\\.", -1));
        for (String word : words) {
            if (word.isEmpty()) {
                throw new IllegalArgumentException(
                        "Pattern '%s' has an empty word; separate words with a single '.'".formatted(text));
            }
            if ((word.contains(ONE) || word.contains(MANY)) && !word.equals(ONE) && !word.equals(MANY)) {
                throw new IllegalArgumentException(
                        "Word '%s' of pattern '%s' mixes a wildcard with other characters; '*' and '#' must be a whole word"
                                .formatted(word, text));
            }
        }
        return new ResourcePattern(text, words);
    }

    /** Whether {@code name} matches this pattern. */
    public boolean matches(String name) {
        Nfa nfa = new Nfa(words);
        BitSet positions = nfa.start();
        for (String word : name.split("\\.", -1)) {
            positions = nfa.step(positions, word);
            if (positions.isEmpty()) {
                return false;
            }
        }
        return nfa.accepts(positions);
    }

    /** Whether some name matches both patterns. */
    public static boolean overlaps(ResourcePattern a, ResourcePattern b) {
        List<String> x = a.words;
        List<String> y = b.words;
        // meet[i][j]: some name sequence matches both x[i..] and y[j..].
        boolean[][] meet = new boolean[x.size() + 1][y.size() + 1];
        for (int i = x.size(); i >= 0; i--) {
            for (int j = y.size(); j >= 0; j--) {
                meet[i][j] = meets(x, y, meet, i, j);
            }
        }
        return meet[0][0];
    }

    private static boolean meets(List<String> x, List<String> y, boolean[][] meet, int i, int j) {
        if (i == x.size() && j == y.size()) {
            return true;
        }
        boolean xMany = i < x.size() && x.get(i).equals(MANY);
        boolean yMany = j < y.size() && y.get(j).equals(MANY);
        if (!xMany && !yMany) {
            return i < x.size() && j < y.size() && sameWord(x.get(i), y.get(j)) && meet[i + 1][j + 1];
        }
        // A '#' takes no word, or takes the word the other side's next token takes, and stays.
        boolean viaX = xMany && (meet[i + 1][j] || (j < y.size() && !yMany && meet[i][j + 1]));
        boolean viaY = yMany && (meet[i][j + 1] || (i < x.size() && !xMany && meet[i + 1][j]));
        return viaX || viaY;
    }

    private static boolean sameWord(String x, String y) {
        return x.equals(ONE) || y.equals(ONE) || x.equals(y);
    }

    /**
     * Whether every name (of at least one word) matching {@code inner} also matches {@code outer}. Containment of these
     * patterns is intractable in general, so this walks both patterns' automata together and is
     * exponential in the worst case; patterns are a few words long.
     *
     * <p>It asks about one outer pattern: a name set that only a union of several patterns covers is
     * reported as not covered.
     */
    public static boolean covers(ResourcePattern outer, ResourcePattern inner) {
        Nfa outerNfa = new Nfa(outer.words);
        Nfa innerNfa = new Nfa(inner.words);
        Set<String> alphabet = new TreeSet<>();
        alphabet.add(OTHER);
        outer.words.forEach(word -> addLiteral(alphabet, word));
        inner.words.forEach(word -> addLiteral(alphabet, word));
        Pair start = new Pair(innerNfa.start(), outerNfa.start());
        Set<Pair> seen = new HashSet<>(Set.of(start));
        ArrayDeque<Pair> queue = new ArrayDeque<>(List.of(start));
        while (!queue.isEmpty()) {
            Pair pair = queue.poll();
            for (String word : alphabet) {
                BitSet next = innerNfa.step(pair.inner, word);
                if (next.isEmpty()) {
                    continue;
                }
                Pair successor = new Pair(next, outerNfa.step(pair.outer, word));
                // A name has at least one word, so the empty sequence a '#' alone would match is not checked.
                if (innerNfa.accepts(next) && !outerNfa.accepts(successor.outer)) {
                    return false;
                }
                if (seen.add(successor)) {
                    queue.add(successor);
                }
            }
        }
        return true;
    }

    /** Where the inner and the outer pattern are after the same words. */
    private record Pair(BitSet inner, BitSet outer) {}

    private static void addLiteral(Set<String> alphabet, String word) {
        if (!word.equals(ONE) && !word.equals(MANY)) {
            alphabet.add(word);
        }
    }

    @Override
    public String toString() {
        return text;
    }

    /** The pattern's words as a nondeterministic automaton: state {@code i} is "words before {@code i} are matched". */
    private record Nfa(List<String> words) {

        BitSet start() {
            BitSet positions = new BitSet();
            positions.set(0);
            return closure(positions);
        }

        BitSet step(BitSet positions, String word) {
            BitSet next = new BitSet();
            for (int p = positions.nextSetBit(0); p >= 0 && p < words.size(); p = positions.nextSetBit(p + 1)) {
                String token = words.get(p);
                if (token.equals(MANY)) {
                    next.set(p);
                } else if (token.equals(ONE) || token.equals(word)) {
                    next.set(p + 1);
                }
            }
            return closure(next);
        }

        boolean accepts(BitSet positions) {
            return positions.get(words.size());
        }

        /** A '#' may take no word, so reaching it also reaches the state after it. */
        private BitSet closure(BitSet positions) {
            for (int p = positions.nextSetBit(0); p >= 0 && p < words.size(); p = positions.nextSetBit(p + 1)) {
                if (words.get(p).equals(MANY)) {
                    positions.set(p + 1);
                }
            }
            return positions;
        }
    }
}
