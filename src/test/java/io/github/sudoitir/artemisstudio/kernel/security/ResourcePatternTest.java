package io.github.sudoitir.artemisstudio.kernel.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Pattern syntax, matching, and the exact overlap and containment tests (team-access spec, design D2). */
class ResourcePatternTest {

    private static ResourcePattern p(String text) {
        return ResourcePattern.parse(text);
    }

    @ParameterizedTest
    @CsvSource({
        "'', empty",
        "a..b, empty word",
        "'.a', empty word",
        "'a.', empty word",
        "a*, mixes a wildcard",
        "'#x', mixes a wildcard",
        "a.*b.c, mixes a wildcard",
        "'a.##', mixes a wildcard"
    })
    void refusesAMalformedPatternNamingTheFault(String text, String fault) {
        assertThatThrownBy(() -> ResourcePattern.parse(text))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(fault);
    }

    @Test
    void refusesANullPattern() {
        assertThatThrownBy(() -> ResourcePattern.parse(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
    }

    @ParameterizedTest
    @CsvSource({
        "orders.#, orders, true",
        "orders.#, orders.in, true",
        "orders.#, orders.a.b.c, true",
        "orders.#, order, false",
        "orders.#, billing.in, false",
        "orders.*, orders.in, true",
        "orders.*, orders, false",
        "orders.*, orders.a.b, false",
        "#, anything.at.all, true",
        "*, one, true",
        "*, one.two, false",
        "#.dlq, orders.in.dlq, true",
        "#.dlq, dlq, true",
        "#.dlq, orders.dlq.old, false",
        "a.#.z, a.z, true",
        "a.#.z, a.b.c.z, true",
        "a.#.z, a.b, false",
        "exact.name, exact.name, true",
        "exact.name, exact.names, false"
    })
    void matchesNamesByWordsWithStarOneWordAndHashManyWords(String pattern, String name, boolean expected) {
        assertThat(p(pattern).matches(name)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
        "orders.#, orders.audit.*, true",
        "orders.#, billing.#, false",
        "orders.*, orders.#, true",
        "orders.*, orders.*.*, false",
        "#, anything, true",
        "a.*, *.b, true",
        "a.b, a.c, false",
        "#.x, x.#, true",
        "a.#, b.#, false",
        "orders.in, orders.in, true"
    })
    void overlapsWhenSomeNameMatchesBoth(String a, String b, boolean expected) {
        assertThat(ResourcePattern.overlaps(p(a), p(b))).isEqualTo(expected);
        assertThat(ResourcePattern.overlaps(p(b), p(a))).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
        "orders.#, orders.events.#, true",
        "orders.#, orders, true",
        "orders.#, orders.*, true",
        "orders.*, orders.#, false",
        "orders.#, billing.#, false",
        "#, orders.#, true",
        "orders.#, #, false",
        "*.#, #.x, true",
        "a.#.z, a.b.z, true",
        "a.#.z, a.*, false",
        "orders.in, orders.in, true",
        "orders.*, orders.in, true"
    })
    void coversWhenEveryNameOfTheInnerPatternMatchesTheOuter(String outer, String inner, boolean expected) {
        assertThat(ResourcePattern.covers(p(outer), p(inner))).isEqualTo(expected);
    }

    @Test
    void coversIsAboutOneOuterPatternNotTheirUnion() {
        // 'a.#' and 'b.#' together cover nothing that '*.#' names, and no one pattern covers '*.#'.
        assertThat(ResourcePattern.covers(p("a.#"), p("*.#"))).isFalse();
        assertThat(ResourcePattern.covers(p("b.#"), p("*.#"))).isFalse();
    }

    /**
     * The answers of all three operations for every pattern of up to four words over {@code a}, {@code b},
     * {@code *} and {@code #}, compared with enumerating every name of up to six words over {@code a},
     * {@code b} and a third word {@code c} that no pattern names. Six words is as long as the shortest
     * shared name of two such patterns can be (three fixed words each, around a '#').
     */
    @Test
    void agreesWithBruteForceEnumerationOverEveryShortPatternPair() {
        List<String> names = names(6);
        List<ResourcePattern> patterns = patterns(4);
        List<BitSet> languages = new ArrayList<>();
        for (ResourcePattern pattern : patterns) {
            BitSet language = new BitSet(names.size());
            for (int i = 0; i < names.size(); i++) {
                language.set(i, pattern.matches(names.get(i)));
            }
            languages.add(language);
        }
        List<String> wrong = new ArrayList<>();
        for (int i = 0; i < patterns.size(); i++) {
            for (int j = 0; j < patterns.size(); j++) {
                BitSet both = (BitSet) languages.get(i).clone();
                both.and(languages.get(j));
                if (ResourcePattern.overlaps(patterns.get(i), patterns.get(j)) != !both.isEmpty()) {
                    wrong.add("overlaps(" + patterns.get(i) + ", " + patterns.get(j) + ")");
                }
                BitSet outsideOuter = (BitSet) languages.get(j).clone();
                outsideOuter.andNot(languages.get(i));
                if (ResourcePattern.covers(patterns.get(i), patterns.get(j)) != outsideOuter.isEmpty()) {
                    wrong.add("covers(" + patterns.get(i) + ", " + patterns.get(j) + ")");
                }
            }
        }
        assertThat(wrong).isEmpty();
    }

    @Test
    void matchesAgreesWithAPlainRecursiveDefinitionOfTheSyntax() {
        for (ResourcePattern pattern : patterns(4)) {
            for (String name : names(5)) {
                assertThat(pattern.matches(name))
                        .as("%s against %s", pattern, name)
                        .isEqualTo(recursive(pattern.words(), 0, List.of(name.split("\\.")), 0));
            }
        }
    }

    private static boolean recursive(List<String> pattern, int p, List<String> name, int n) {
        if (p == pattern.size()) {
            return n == name.size();
        }
        return switch (pattern.get(p)) {
            case "#" -> recursive(pattern, p + 1, name, n) || (n < name.size() && recursive(pattern, p, name, n + 1));
            case "*" -> n < name.size() && recursive(pattern, p + 1, name, n + 1);
            default -> n < name.size() && pattern.get(p).equals(name.get(n)) && recursive(pattern, p + 1, name, n + 1);
        };
    }

    private static List<String> names(int maxWords) {
        List<String> names = new ArrayList<>();
        List<String> level = List.of("");
        for (int length = 1; length <= maxWords; length++) {
            List<String> next = new ArrayList<>();
            for (String prefix : level) {
                for (String word : List.of("a", "b", "c")) {
                    next.add(prefix.isEmpty() ? word : prefix + "." + word);
                }
            }
            names.addAll(next);
            level = next;
        }
        return names;
    }

    private static List<ResourcePattern> patterns(int maxWords) {
        List<ResourcePattern> patterns = new ArrayList<>();
        List<String> level = List.of("");
        for (int length = 1; length <= maxWords; length++) {
            List<String> next = new ArrayList<>();
            for (String prefix : level) {
                for (String word : List.of("a", "b", "*", "#")) {
                    next.add(prefix.isEmpty() ? word : prefix + "." + word);
                }
            }
            next.forEach(text -> patterns.add(ResourcePattern.parse(text)));
            level = next;
        }
        return patterns;
    }
}
