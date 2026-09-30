package io.github.sudoitir.artemisstudio.architecture;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.kernel.lifecycle.LifecycleRegistry;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.RegisteredStore;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * No table grows unnoticed (ADR-0134): every table a changelog creates either belongs to a store
 * under the data lifecycle, is a partition of one, or is listed here as bounded by design, with why.
 * A new table that is none of these fails the build until it gets a store or a reason.
 */
class StoreCoverageTest extends PostgresIntegrationTest {

    private static final Pattern CREATE = Pattern.compile("^CREATE TABLE (\\w+)", Pattern.MULTILINE);

    /** Tables that do not grow with use, and why. */
    private static final Map<String, String> BOUNDED_BY_DESIGN = Map.ofEntries(
            Map.entry("app_user", "configuration: one row per user"),
            Map.entry("role", "configuration"),
            Map.entry("role_permission", "configuration"),
            Map.entry("user_role", "configuration"),
            Map.entry("identity_group_mapping", "configuration"),
            Map.entry("identity_provider_default_role", "configuration"),
            Map.entry("plugin_secret", "configuration: one row per plugin secret"),
            Map.entry("plugin_artifact", "one row per installed plugin version, removed on uninstall"),
            Map.entry("plugin_install", "one row per installed plugin"),
            Map.entry("plugin_installer", "configuration"),
            Map.entry("studio_boot", "trimmed to a day at every start"),
            Map.entry("plugin_upload", "trimmed to a day on every upload"),
            Map.entry("studio_config_property", "configuration"),
            Map.entry("studio_setting", "configuration: one row per overridden setting"),
            Map.entry("shedlock", "one row per installation-wide job"),
            Map.entry("lifecycle_purge", "one row per store"),
            Map.entry("governance_policy", "configuration"),
            Map.entry("governance_rule", "configuration"),
            Map.entry("broker_credential", "configuration: one row per cluster"),
            Map.entry("broker_node", "configuration: one row per broker node"),
            Map.entry("broker_tls", "configuration"),
            Map.entry("cluster", "configuration"),
            Map.entry("environment", "configuration"),
            Map.entry("queue_snapshot", "current state: stale rows are reaped on every scrape sweep"),
            Map.entry("api_token", "configuration"),
            Map.entry("api_token_grant", "configuration"),
            Map.entry("alert_rule", "configuration"),
            Map.entry("alert_rule_channel", "configuration"),
            Map.entry("alert_rule_seed", "one row per seeded plugin rule"),
            Map.entry("alert_state", "current state: one row per non-OK subject"),
            Map.entry("notification_channel", "configuration"),
            Map.entry("rr_expectation", "configuration"),
            Map.entry("message_capture_node", "current state: one row per capturing node"),
            Map.entry("message_index_subscription", "configuration"),
            Map.entry("broker_config_declaration", "configuration"),
            Map.entry("broker_config_node_state", "current state: one row per node"),
            Map.entry("broker_config_owned_item", "current state: what Studio created on the brokers"),
            Map.entry("flow_client_edge", "current state: trimmed on every flow sample"),
            Map.entry("flow_demand", "current state: trimmed on every flow sample"),
            Map.entry("flow_node_sample", "current state: trimmed on every flow sample"),
            Map.entry("flow_route", "current state: trimmed on every flow sample"),
            Map.entry("setup_finding_acceptance", "configuration: an operator's accepted risks"),
            Map.entry("plugin_message_registration", "configuration"),
            Map.entry("plugin_message_registration_node", "current state: one row per node and registration"));

    @Autowired
    LifecycleRegistry registry;

    @Test
    void everyTableThatGrowsWithUseBelongsToAStore() throws IOException {
        Set<String> stored = registry.all().stream()
                .map(RegisteredStore::def)
                .flatMap(def -> def.tables().stream())
                .collect(Collectors.toSet());
        Set<String> uncovered = new TreeSet<>();
        for (String table : createdTables()) {
            boolean partition = table.endsWith("_default") && stored.contains(table.substring(0, table.length() - 8));
            if (!stored.contains(table) && !partition && !BOUNDED_BY_DESIGN.containsKey(table)) {
                uncovered.add(table);
            }
        }
        assertThat(uncovered)
                .as("tables with no store under the data lifecycle and no reason in BOUNDED_BY_DESIGN")
                .isEmpty();
    }

    @Test
    void theAllowlistNamesOnlyRealTablesThatNoStoreClaims() throws IOException {
        Set<String> stored =
                registry.all().stream().flatMap(s -> s.def().tables().stream()).collect(Collectors.toSet());
        assertThat(createdTables()).containsAll(BOUNDED_BY_DESIGN.keySet());
        assertThat(BOUNDED_BY_DESIGN.keySet()).doesNotContainAnyElementsOf(stored);
    }

    private static Set<String> createdTables() throws IOException {
        Set<String> tables = new TreeSet<>();
        for (Resource changeset :
                new PathMatchingResourcePatternResolver().getResources("classpath:db/changelog/*/*/changes/*.sql")) {
            CREATE.matcher(changeset.getContentAsString(UTF_8)).results().forEach(m -> tables.add(m.group(1)));
        }
        return tables;
    }
}
