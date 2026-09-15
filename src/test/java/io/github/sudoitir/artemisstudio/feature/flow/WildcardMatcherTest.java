package io.github.sudoitir.artemisstudio.feature.flow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WildcardMatcherTest {

    @Test
    void hashMatchesZeroOrMoreWords() {
        assertThat(WildcardMatcher.matches("orders.#", "orders")).isTrue();
        assertThat(WildcardMatcher.matches("orders.#", "orders.eu")).isTrue();
        assertThat(WildcardMatcher.matches("orders.#", "orders.eu.retail")).isTrue();
        assertThat(WildcardMatcher.matches("#", "anything.at.all")).isTrue();
        assertThat(WildcardMatcher.matches("orders.#", "payments.eu")).isFalse();
    }

    @Test
    void starMatchesExactlyOneWord() {
        assertThat(WildcardMatcher.matches("orders.*", "orders.eu")).isTrue();
        assertThat(WildcardMatcher.matches("orders.*", "orders")).isFalse();
        assertThat(WildcardMatcher.matches("orders.*", "orders.eu.retail")).isFalse();
        assertThat(WildcardMatcher.matches("*.eu", "orders.eu")).isTrue();
    }

    @Test
    void aPlainAddressMatchesOnlyItself() {
        assertThat(WildcardMatcher.isWildcard("orders.eu")).isFalse();
        assertThat(WildcardMatcher.matches("orders.eu", "orders.eu")).isTrue();
        assertThat(WildcardMatcher.matches("orders.eu", "orders.us")).isFalse();
        assertThat(WildcardMatcher.isWildcard("orders.#")).isTrue();
    }
}
