package io.github.sudoitir.artemisstudio.feature.queues;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The two judgements a divert creation rests on, before and after the broker call. */
class DivertOperationsTest {

    private static DivertRow divert(String name, String from, String to) {
        return new DivertRow(null, null, name, name, from, to, null, "STRIP", null, Map.of(), false, false);
    }

    @Test
    void aDivertBackToItsOwnSourceIsACycleAndTheCycleIsNamed() {
        List<DivertRow> existing = List.of(divert("ab", "A", "B"), divert("bc", "B", "C"));

        assertThat(DivertOperations.cycle(existing, "ca", "C", "A")).isEqualTo("C → A → B → C");
        assertThat(DivertOperations.cycle(existing, "cd", "C", "D")).isNull();
    }

    @Test
    void theDivertBeingCreatedDoesNotCountAgainstItself() {
        // Recreating an existing divert under its own name replaces nothing and closes no loop.
        assertThat(DivertOperations.cycle(List.of(divert("ba", "B", "A")), "ba", "A", "B"))
                .isNull();
    }

    @Test
    void differencesNameOnlyTheFieldsThatDiffer() {
        DivertRow deployed = divert("d", "A", "B");

        assertThat(DivertOperations.differences(
                        deployed, DivertOperations.divertConfig("d", null, "A", "B", false, null, null)))
                .isEmpty();
        assertThat(DivertOperations.differences(
                        deployed, DivertOperations.divertConfig("d", null, "A", "ELSEWHERE", true, "x = 1", null)))
                .containsExactly("forwarding-address", "filter-string", "exclusive");
    }
}
