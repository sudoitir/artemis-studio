package io.github.sudoitir.artemisstudio.domain.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.domain.config.ConfigDiff.Classification;
import io.github.sudoitir.artemisstudio.domain.config.ConfigDiff.Entry;
import io.github.sudoitir.artemisstudio.domain.config.ConfigDiff.KeyStatus;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The comparison's semantics (ADR-0043): three-way key status, address settings
 * keyed by their {@code match} rather than by position, an expected class distinct
 * from drift, and nothing dropped without the operator being told.
 */
class ConfigDiffTest {

    private final JsonMapper mapper = new JsonMapper();

    private JsonNode json(String text) {
        return mapper.readTree(text);
    }

    private Entry entry(List<Entry> entries, String key) {
        return entries.stream().filter(e -> e.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void reportsSameDifferentAndOnlyInOneSide() {
        List<Entry> entries = ConfigDiff.compare(
                ConfigDiff.SECTION_BROKER,
                Map.of("/JournalFileSize", "10485760", "/JournalType", "ASYNCIO", "/GlobalMaxSize", "512"),
                Map.of("/JournalFileSize", "10485760", "/JournalType", "NIO", "/MaxDiskUsage", "90"));

        assertThat(entry(entries, "/JournalFileSize").status()).isEqualTo(KeyStatus.SAME);
        assertThat(entry(entries, "/JournalType").status()).isEqualTo(KeyStatus.DIFFERENT);
        assertThat(entry(entries, "/GlobalMaxSize").status()).isEqualTo(KeyStatus.ONLY_IN_LEFT);
        assertThat(entry(entries, "/MaxDiskUsage").status()).isEqualTo(KeyStatus.ONLY_IN_RIGHT);
    }

    @Test
    void aKeyMissingOnOneSideIsNotAnEmptyValuedDifference() {
        List<Entry> entries = ConfigDiff.compare(ConfigDiff.SECTION_BROKER, Map.of("/GlobalMaxSize", "512"), Map.of());

        Entry only = entry(entries, "/GlobalMaxSize");
        assertThat(only.status()).isEqualTo(KeyStatus.ONLY_IN_LEFT);
        assertThat(only.right()).isNull();
    }

    @Test
    void aDifferingConfigurationKeyIsDrift() {
        List<Entry> entries = ConfigDiff.compare(
                ConfigDiff.SECTION_BROKER, Map.of("/JournalType", "ASYNCIO"), Map.of("/JournalType", "NIO"));

        assertThat(entry(entries, "/JournalType").classification()).isEqualTo(Classification.CONFIGURATION);
        assertThat(entry(entries, "/JournalType").isDrift()).isTrue();
    }

    @Test
    void theBrokerNameClassifiesAsExpectedNotDrift() {
        // The dev pair is broker="primary" / broker="backup" by design.
        List<Entry> entries =
                ConfigDiff.compare(ConfigDiff.SECTION_BROKER, Map.of("/Name", "primary"), Map.of("/Name", "backup"));

        Entry name = entry(entries, "/Name");
        assertThat(name.status()).isEqualTo(KeyStatus.DIFFERENT);
        assertThat(name.classification()).isEqualTo(Classification.EXPECTED);
        assertThat(name.isDrift()).isFalse();
    }

    @Test
    void haPolicyAndNodeLocalPathsAreAlsoExpected() {
        List<Entry> entries = ConfigDiff.compare(
                ConfigDiff.SECTION_BROKER,
                Map.of("/HAPolicy", "Replication Primary w/quorum voting", "/JournalDirectory", "/a/journal"),
                Map.of("/HAPolicy", "Replication Backup w/quorum voting", "/JournalDirectory", "/b/journal"));

        assertThat(entries).allSatisfy(e -> assertThat(e.isDrift()).isFalse());
    }

    @Test
    void anAttributeStudioDoesNotKnowLandsInUnclassifiedRatherThanDisappearing() {
        // Whatever Artemis adds next must still be visible, and must not read as drift.
        List<Entry> entries = ConfigDiff.compare(
                ConfigDiff.SECTION_BROKER,
                Map.of("/SomeFutureArtemisAttribute", "1"),
                Map.of("/SomeFutureArtemisAttribute", "2"));

        Entry unknown = entry(entries, "/SomeFutureArtemisAttribute");
        assertThat(unknown.classification()).isEqualTo(Classification.UNCLASSIFIED);
        assertThat(unknown.isDrift()).isFalse();
    }

    @Test
    void runtimeCountersDoNotReadAsDrift() {
        List<Entry> entries = ConfigDiff.compare(
                ConfigDiff.SECTION_BROKER,
                Map.of("/TotalMessageCount", "7", "/ConnectionCount", "4"),
                Map.of("/TotalMessageCount", "0", "/ConnectionCount", "0"));

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
                Map.of("/AuthenticationCacheSize", "1", "/AuthorizationCacheSize", "2"),
                Map.of("/AuthenticationCacheSize", "0", "/AuthorizationCacheSize", "0"));

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
                ConfigDiff.flattenKeyed(left, "match"),
                ConfigDiff.flattenKeyed(right, "match"));

        assertThat(entries).isNotEmpty();
        assertThat(entries).allSatisfy(e -> assertThat(e.status()).isEqualTo(KeyStatus.SAME));
    }

    @Test
    void anAddressSettingOnOnlyOneSideIsReportedUnderItsMatchPattern() {
        JsonNode left =
                json("[ {\"match\":\"#\",\"maxSizeBytes\":-1}, {\"match\":\"orders.#\",\"maxSizeBytes\":1024} ]");
        JsonNode right = json("[ {\"match\":\"#\",\"maxSizeBytes\":-1} ]");

        List<Entry> entries = ConfigDiff.compare(
                ConfigDiff.SECTION_ADDRESS_SETTINGS,
                ConfigDiff.flattenKeyed(left, "match"),
                ConfigDiff.flattenKeyed(right, "match"));

        Entry onlyLeft = entry(entries, "/orders.#/maxSizeBytes");
        assertThat(onlyLeft.status()).isEqualTo(KeyStatus.ONLY_IN_LEFT);
        assertThat(onlyLeft.isDrift()).isTrue();
    }

    @Test
    void aDifferingAddressSettingIsDrift() {
        JsonNode left = json("[ {\"match\":\"#\",\"maxSizeBytes\":-1} ]");
        JsonNode right = json("[ {\"match\":\"#\",\"maxSizeBytes\":1024} ]");

        List<Entry> entries = ConfigDiff.compare(
                ConfigDiff.SECTION_ADDRESS_SETTINGS,
                ConfigDiff.flattenKeyed(left, "match"),
                ConfigDiff.flattenKeyed(right, "match"));

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
                ConfigDiff.flattenKeyed(left, "name"),
                ConfigDiff.flattenKeyed(right, "name"));

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
                ConfigDiff.flattenKeyed(left, "name"),
                ConfigDiff.flattenKeyed(right, "name"));

        assertThat(entry(entries, "/amq/send").status()).isEqualTo(KeyStatus.SAME);
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
    void statusIsAlsoAWordSoTheUiNeverCarriesItByColourAlone() {
        assertThat(ConfigDiff.statusWord(KeyStatus.SAME, "primary", "backup")).isEqualTo("same");
        assertThat(ConfigDiff.statusWord(KeyStatus.DIFFERENT, "primary", "backup"))
                .isEqualTo("different");
        assertThat(ConfigDiff.statusWord(KeyStatus.ONLY_IN_LEFT, "primary", "backup"))
                .isEqualTo("only on primary");
        assertThat(ConfigDiff.statusWord(KeyStatus.ONLY_IN_RIGHT, "primary", "backup"))
                .isEqualTo("only on backup");
    }
}
