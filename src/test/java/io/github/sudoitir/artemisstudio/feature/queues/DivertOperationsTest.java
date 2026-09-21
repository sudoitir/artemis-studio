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

    /**
     * The broker answers 200 for a transformer shape it ignores, so a divert deployed
     * without its transformer must read back as different, never as already in place.
     */
    @Test
    void aMissingOrDifferentTransformerIsADifference() {
        Map<String, Object> withTransformer =
                new java.util.LinkedHashMap<>(DivertOperations.divertConfig("d", null, "A", "B", false, null, null));
        withTransformer.put(
                "transformer-configuration", Map.of("class-name", "com.example.T", "properties", Map.of("k", "v")));
        DivertRow bare = divert("d", "A", "B");
        DivertRow transformed = new DivertRow(
                null, null, "d", "d", "A", "B", null, "STRIP", "com.example.T", Map.of("k", "v"), false, false);
        DivertRow otherProps = new DivertRow(
                null, null, "d", "d", "A", "B", null, "STRIP", "com.example.T", Map.of("k", "x"), false, false);

        assertThat(DivertOperations.differences(bare, withTransformer)).containsExactly("transformer class-name");
        assertThat(DivertOperations.differences(transformed, withTransformer)).isEmpty();
        assertThat(DivertOperations.differences(otherProps, withTransformer)).containsExactly("transformer properties");
        assertThat(DivertOperations.differences(
                        transformed, DivertOperations.divertConfig("d", null, "A", "B", false, null, null)))
                .containsExactly("transformer class-name");
    }
}
