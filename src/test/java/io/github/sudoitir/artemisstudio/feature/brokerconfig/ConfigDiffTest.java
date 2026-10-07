package io.github.sudoitir.artemisstudio.feature.brokerconfig;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.feature.brokerconfig.ConfigDiff.Classification;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.ConfigDiff.Entry;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.ConfigDiff.Side;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.ConfigDiff.State;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The comparison's semantics (ADR-0043, ADR-0178): a majority and its outliers across
 * every node, address settings keyed by their {@code match} rather than by position, an
 * expected class distinct from drift, and nothing dropped without the operator being told.
 */
class ConfigDiffTest {

    private final JsonMapper mapper = new JsonMapper();

    private JsonNode json(String text) {
        return mapper.readTree(text);
    }

    private Entry entry(List<Entry> entries, String key) {
        return entries.stream().filter(e -> e.key().equals(key)).findFirst().orElseThrow();
    }

    /** Nodes named n1, n2, … each holding one flattened map. */
    @SafeVarargs
    private static List<Side> sides(Map<String, String>... values) {
        List<Side> sides = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            sides.add(new Side(UUID.randomUUID(), "n" + (i + 1), values[i], key -> true));
        }
        return sides;
    }

    private static List<Side> pair(Map<String, String> left, Map<String, String> right) {
        return sides(left, right);
    }

    private static List<String> names(List<ConfigDiff.NodeValue> values) {
        return values.stream().map(ConfigDiff.NodeValue::nodeName).toList();
    }

    @Test
    void oneNodeDifferingFromThreeIsTheOutlierAndTheRestAreTheMajority() {
        Map<String, String> same = Map.of("/JournalType", "ASYNCIO");
        List<Entry> entries =
                ConfigDiff.compare(ConfigDiff.SECTION_BROKER, sides(same, same, Map.of("/JournalType", "NIO"), same));

        Entry type = entry(entries, "/JournalType");
        assertThat(type.state()).isEqualTo(State.DIFFERENT);
        assertThat(type.majority()).isEqualTo("ASYNCIO");
        assertThat(names(type.outliers())).containsExactly("n3");
        assertThat(type.outliers().getFirst().value()).isEqualTo("NIO");
        assertThat(type.groups()).isEmpty();
        assertThat(type.isDrift()).isTrue();
    }

    @Test
    void aKeyTheNodesAgreeOnHasAMajorityAndNoOutliers() {
        Map<String, String> same = Map.of("/JournalFileSize", "10485760");
        Entry size = entry(ConfigDiff.compare(ConfigDiff.SECTION_BROKER, sides(same, same, same)), "/JournalFileSize");

        assertThat(size.state()).isEqualTo(State.SAME);
        assertThat(size.majority()).isEqualTo("10485760");
        assertThat(size.outliers()).isEmpty();
        assertThat(size.isDrift()).isFalse();
    }

    @Test
    void halfAndHalfHasNoMajorityAndListsEveryValueWithItsNodes() {
        Map<String, String> a = Map.of("/JournalType", "ASYNCIO");
        Map<String, String> b = Map.of("/JournalType", "NIO");
        Entry type = entry(ConfigDiff.compare(ConfigDiff.SECTION_BROKER, sides(a, b, a, b)), "/JournalType");

        assertThat(type.majority()).isNull();
        assertThat(type.outliers()).isEmpty();
        assertThat(type.groups()).hasSize(2);
        assertThat(type.groups().get(0).value()).isEqualTo("ASYNCIO");
        assertThat(names(type.groups().get(0).nodes())).containsExactly("n1", "n3");
        assertThat(names(type.groups().get(1).nodes())).containsExactly("n2", "n4");
        assertThat(type.isDrift()).isTrue();
    }

    @Test
    void aKeyMissingOnOneNodeIsMissingThereNotAnEmptyValuedDifference() {
        Map<String, String> has = Map.of("/GlobalMaxSize", "512");
        Entry max =
                entry(ConfigDiff.compare(ConfigDiff.SECTION_BROKER, sides(has, has, has, Map.of())), "/GlobalMaxSize");

        assertThat(max.state()).isEqualTo(State.MISSING_ON_SOME);
        assertThat(max.majority()).isEqualTo("512");
        assertThat(names(max.outliers())).containsExactly("n4");
        assertThat(max.outliers().getFirst().missing()).isTrue();
        assertThat(max.outliers().getFirst().value()).isNull();
        assertThat(max.isDrift()).isTrue();
    }

    @Test
    void aNodeWhoseSurfaceCannotExposeAKeyIsLeftOutInsteadOfMissing() {
        Map<String, String> has = Map.of("/GlobalMaxSize", "512");
        List<Side> sides = List.of(
                new Side(UUID.randomUUID(), "n1", has, key -> true),
                new Side(UUID.randomUUID(), "passive", Map.of(), key -> false));

        Entry max = entry(ConfigDiff.compare(ConfigDiff.SECTION_BROKER, sides), "/GlobalMaxSize");

        assertThat(names(max.values())).containsExactly("n1");
        assertThat(max.state()).isEqualTo(State.SAME);
        assertThat(max.outliers()).isEmpty();
    }

    @Test
    void aDifferingConfigurationKeyIsDrift() {
        List<Entry> entries = ConfigDiff.compare(
                ConfigDiff.SECTION_BROKER, pair(Map.of("/JournalType", "ASYNCIO"), Map.of("/JournalType", "NIO")));

        assertThat(entry(entries, "/JournalType").classification()).isEqualTo(Classification.DRIFT);
        assertThat(entry(entries, "/JournalType").isDrift()).isTrue();
    }

    @Test
    void theBrokerNameClassifiesAsExpectedNotDrift() {
        // The dev pair is broker="primary" / broker="backup" by design.
        List<Entry> entries = ConfigDiff.compare(
                ConfigDiff.SECTION_BROKER, pair(Map.of("/Name", "primary"), Map.of("/Name", "backup")));

        Entry name = entry(entries, "/Name");
        assertThat(name.state()).isEqualTo(State.DIFFERENT);
        assertThat(name.classification()).isEqualTo(Classification.EXPECTED);
        assertThat(name.isDrift()).isFalse();
        assertThat(name.isExpected()).isTrue();
    }

    @Test
    void haPolicyAndNodeLocalPathsAreAlsoExpected() {
        List<Entry> entries = ConfigDiff.compare(
                ConfigDiff.SECTION_BROKER,
                pair(
                        Map.of("/HAPolicy", "Replication Primary w/quorum voting", "/JournalDirectory", "/a/journal"),
                        Map.of("/HAPolicy", "Replication Backup w/quorum voting", "/JournalDirectory", "/b/journal")));

        assertThat(entries).allSatisfy(e -> assertThat(e.isDrift()).isFalse());
    }

    @Test
    void anAttributeStudioDoesNotKnowLandsInUnclassifiedRatherThanDisappearing() {
        // Whatever Artemis adds next must still be visible, and must not read as drift.
        List<Entry> entries = ConfigDiff.compare(
                ConfigDiff.SECTION_BROKER,
                pair(Map.of("/SomeFutureArtemisAttribute", "1"), Map.of("/SomeFutureArtemisAttribute", "2")));

        Entry unknown = entry(entries, "/SomeFutureArtemisAttribute");
        assertThat(unknown.classification()).isEqualTo(Classification.UNCLASSIFIED);
        assertThat(unknown.isDrift()).isFalse();
    }

    @Test
    void runtimeCountersDoNotReadAsDrift() {
        List<Entry> entries = ConfigDiff.compare(
                ConfigDiff.SECTION_BROKER,
                pair(
                        Map.of("/TotalMessageCount", "7", "/ConnectionCount", "4"),
                        Map.of("/TotalMessageCount", "0", "/ConnectionCount", "0")));

        assertThat(entries).allSatisfy(e -> {
            assertThat(e.classification()).isEqualTo(Classification.UNCLASSIFIED);
            assertThat(e.isDrift()).isFalse();
        });
    }

    @Test
    void cacheOccupancyAttributesAreNotConfigurationDespiteTheirNames() {
        // AuthenticationCacheSize reports the cache's current occupancy, not the
        // configured maximum: a healthy pair reads 1 on the primary and 0 on the
        // passive backup. Classifying it by name alone made every healthy pair report
        // two drifts — caught by a live comparison, not by reading the attribute list.
        List<Entry> entries = ConfigDiff.compare(
                ConfigDiff.SECTION_BROKER,
                pair(
                        Map.of("/AuthenticationCacheSize", "1", "/AuthorizationCacheSize", "2"),
                        Map.of("/AuthenticationCacheSize", "0", "/AuthorizationCacheSize", "0")));

        assertThat(entries).allSatisfy(e -> {
            assertThat(e.classification()).isEqualTo(Classification.UNCLASSIFIED);
            assertThat(e.isDrift()).isFalse();
        });
    }

    @Test
    void addressSettingsAreKeyedByMatchSoReorderingIsNotDrift() {
        JsonNode left = json("""
                [ {"match":"#","maxSizeBytes":-1}, {"match":"orders.#","maxSizeBytes":1024} ]
                """);
        JsonNode right = json("""
                [ {"match":"orders.#","maxSizeBytes":1024}, {"match":"#","maxSizeBytes":-1} ]
                """);

        List<Entry> entries = ConfigDiff.compare(
                ConfigDiff.SECTION_ADDRESS_SETTINGS,
                pair(ConfigDiff.flattenKeyed(left, "match"), ConfigDiff.flattenKeyed(right, "match")));

        assertThat(entries).isNotEmpty();
        assertThat(entries).allSatisfy(e -> assertThat(e.state()).isEqualTo(State.SAME));
    }

    @Test
    void anAddressSettingOnOnlySomeNodesIsReportedUnderItsMatchPattern() {
        JsonNode with =
                json("[ {\"match\":\"#\",\"maxSizeBytes\":-1}, {\"match\":\"orders.#\",\"maxSizeBytes\":1024} ]");
        JsonNode without = json("[ {\"match\":\"#\",\"maxSizeBytes\":-1} ]");

        List<Entry> entries = ConfigDiff.compare(
                ConfigDiff.SECTION_ADDRESS_SETTINGS,
                sides(
                        ConfigDiff.flattenKeyed(with, "match"),
                        ConfigDiff.flattenKeyed(with, "match"),
                        ConfigDiff.flattenKeyed(without, "match")));

        Entry orders = entry(entries, "/orders.#/maxSizeBytes");
        assertThat(orders.state()).isEqualTo(State.MISSING_ON_SOME);
        assertThat(names(orders.outliers())).containsExactly("n3");
        assertThat(orders.isDrift()).isTrue();
    }

    @Test
    void aDifferingAddressSettingIsDrift() {
        JsonNode left = json("[ {\"match\":\"#\",\"maxSizeBytes\":-1} ]");
        JsonNode right = json("[ {\"match\":\"#\",\"maxSizeBytes\":1024} ]");

        List<Entry> entries = ConfigDiff.compare(
                ConfigDiff.SECTION_ADDRESS_SETTINGS,
                pair(ConfigDiff.flattenKeyed(left, "match"), ConfigDiff.flattenKeyed(right, "match")));

        assertThat(entry(entries, "/#/maxSizeBytes").isDrift()).isTrue();
    }

    @Test
    void anAcceptorsHostAndPortAreExpectedButItsProtocolsAreNot() {
        JsonNode left = json("""
                [ {"name":"artemis","params":{"host":"broker-1","port":"61616","protocols":"CORE,AMQP"}} ]
                """);
        JsonNode right = json("""
                [ {"name":"artemis","params":{"host":"broker-2","port":"61617","protocols":"CORE"}} ]
                """);

        List<Entry> entries = ConfigDiff.compare(
                ConfigDiff.SECTION_ACCEPTORS,
                pair(ConfigDiff.flattenKeyed(left, "name"), ConfigDiff.flattenKeyed(right, "name")));

        assertThat(entry(entries, "/artemis/params/host").classification()).isEqualTo(Classification.EXPECTED);
        assertThat(entry(entries, "/artemis/params/port").classification()).isEqualTo(Classification.EXPECTED);
        assertThat(entry(entries, "/artemis/params/protocols").isDrift()).isTrue();
    }

    @Test
    void securitySettingsAreKeyedByRoleName() {
        JsonNode left = json("[ {\"name\":\"amq\",\"consume\":true,\"send\":true} ]");
        JsonNode right = json("[ {\"name\":\"amq\",\"consume\":false,\"send\":true} ]");

        List<Entry> entries = ConfigDiff.compare(
                ConfigDiff.SECTION_SECURITY_SETTINGS,
                pair(ConfigDiff.flattenKeyed(left, "name"), ConfigDiff.flattenKeyed(right, "name")));

        assertThat(entry(entries, "/amq/send").state()).isEqualTo(State.SAME);
        assertThat(entry(entries, "/amq/consume").isDrift()).isTrue();
    }

    @Test
    void anElementWithNoIdentityFieldIsKeptUnderItsIndexRatherThanDropped() {
        JsonNode array = json("[ {\"maxSizeBytes\":-1} ]");

        assertThat(ConfigDiff.flattenKeyed(array, "match")).containsKey("/0/maxSizeBytes");
    }

    @Test
    void aPointerSegmentContainingASlashIsEscaped() {
        // Match patterns are addresses, and an address may contain a slash.
        JsonNode array = json("[ {\"match\":\"a/b\",\"maxSizeBytes\":1} ]");

        assertThat(ConfigDiff.flattenKeyed(array, "match")).containsKey("/a~1b/maxSizeBytes");
    }

    @Test
    void nestedObjectsFlattenToPointers() {
        assertThat(ConfigDiff.flatten(json("{\"a\":{\"b\":1},\"c\":[10,20]}")))
                .containsEntry("/a/b", "1")
                .containsEntry("/c/0", "10")
                .containsEntry("/c/1", "20");
    }

    @Test
    void stateIsAlsoAWordSoTheUiNeverCarriesItByColourAlone() {
        assertThat(ConfigDiff.stateWord(State.SAME)).isEqualTo("same");
        assertThat(ConfigDiff.stateWord(State.DIFFERENT)).isEqualTo("different");
        assertThat(ConfigDiff.stateWord(State.MISSING_ON_SOME)).isEqualTo("missing on some");
    }
}
