package io.github.sudoitir.artemisstudio.architecture;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * The per-module baselines build the schema the previous changelog built, apart from the
 * differences made on purpose (task 7.7, ADR-0072). {@code db/reference-schema.sql} is a
 * {@code pg_dump --schema-only} of a database migrated by the last changelog before the
 * re-baseline; it is compared with a dump of the test database, statement by statement.
 */
class SchemaBaselineDiffTest extends PostgresIntegrationTest {

    /** Every difference the re-baseline made deliberately. Any other difference fails. */
    private static final List<Pattern> DELIBERATE = Stream.of(
                    "ADD CONSTRAINT fk_audit_event_\\w+ FOREIGN KEY", // audit outlives what it names
                    "CREATE TABLE audit_event ", // cluster_name
                    "CREATE TABLE app_user ", // provider_id and external_subject
                    "uq_app_user_(issuer|provider)_subject",
                    "oidc_role_mapping", // replaced by the two tables below
                    "identity_group_mapping",
                    "identity_provider_default_role",
                    // Data governance (ADR-0075), added after the re-baseline in changesets of their own.
                    "governance_rule",
                    "governance_policy",
                    "classification_finding",
                    "policy_version",
                    "CREATE TABLE message_index(_default)? ",
                    // Capture ring bound lowered to 1,000,000 (ADR-0079, changeset feature-sql 0003).
                    "CREATE TABLE message_index_subscription ",
                    // Flow sampling caches (ADR-0081, changeset feature-flow 0001).
                    "flow_(demand|client_edge|node_sample|route)",
                    // BRIDGE added to the owned-item kind check (ADR-0091, changeset feature-brokerconfig 0003).
                    "CREATE TABLE broker_config_owned_item ",
                    // Bulk runs and the audit parent link (ADR-0093, changesets kernel-audit 0002, feature-bulk 0001).
                    "ix_audit_event_parent",
                    "bulk_run",
                    // Cross-broker message transfer (ADR-0097, changeset feature-transfer 0001).
                    "transfer_(run|copied)",
                    // Email, Teams and PagerDuty channel kinds, the CONFIG_DRIFT and SETUP_RISK state
                    // conditions, and the delivery log's index (ADR-0105, changeset feature-alerting 0002).
                    "CREATE TABLE (alert_rule|notification_channel) ",
                    "ix_alert_delivery_channel_seq",
                    // Setup review (ADR-0106, changeset feature-setupreview 0001).
                    "setup_(review|finding|finding_acceptance)",
                    // Runtime plugins (ADR-0099..0103, changesets kernel-plugin 0001..0006).
                    "plugin_(artifact|install|installer|upload)",
                    "studio_boot",
                    // Plugins' secrets and message registrations (ADR-0111, changesets kernel-security 0002,
                    // feature-plugins 0001).
                    "plugin_secret",
                    // Trusted publisher keys and the unverified allowance (ADR-0141, changeset kernel-plugin 0008).
                    "plugin_trust(ed_key|_policy)",
                    "plugin_message_registration",
                    // Plugin metrics (ADR-0113): samples with no broker node (changeset platform-scrape 0002)
                    // and the record of seeded plugin alert rules (changeset feature-alerting 0003).
                    "CREATE TABLE metric_sample(_default)? ",
                    "alert_rule_seed",
                    // Envelope encryption (ADR-0132): one sealed column in every secret store and the current key
                    // version (changesets kernel-security 0003, 0004, platform-clusters 0002, feature-alerting 0004,
                    // feature-sql 0004).
                    "secret_key_state",
                    // Online key-encryption-key rotation (ADR-0132 D5, changeset kernel-security 0005).
                    "secret_rotation",
                    "CREATE TABLE broker_credential ",
                    // Installation-wide job locks (ADR-0125, changeset kernel-jobs 0001).
                    "shedlock",
                    // The data lifecycle's purge record (ADR-0134, changeset kernel-lifecycle 0001).
                    "lifecycle_purge",
                    "storage_sample",
                    // Installation-scoped alert rules (ADR-0135, changeset feature-alerting 0005).
                    "CREATE TABLE alert_firing ",
                    // Token rotation, the lifetime cap, MCP allow-lists and usage (ADR-0136, changeset
                    // feature-apitokens 0002).
                    "CREATE TABLE api_token ",
                    "uq_api_token_previous_prefix",
                    "api_token_usage",
                    // Second factors of local accounts (ADR-0143): their tables (changesets feature-identitylocal
                    // 0001..0003), the passkey tables Spring Security's JDBC repositories read (0002),
                    // role.requires_mfa (kernel-security 0007) and api_token.minted_with_mfa (feature-apitokens 0003).
                    "local_(totp|recovery_code|recovery_key|trusted_device)",
                    "user_(entities|credentials)",
                    "CREATE TABLE role ",
                    // Idempotency keys (ADR-0149, changeset kernel-security 0009).
                    "idempotency_record",
                    // Usernames are unique ignoring case (kernel-security 0008).
                    "uq_app_user_username_lower",
                    // Text bodies of bytes messages are full-text indexed; binary is body_base64 (ADR-0148,
                    // changeset feature-sql 0005).
                    "ix_message_index_body_fts",
                    "message_index_default_to_tsvector_idx")
            .map(Pattern::compile)
            .toList();

    /**
     * Liquibase's own tables, the daily partitions created at runtime, and the schemas plugins own
     * (ADR-0101) — a test that activates a plugin leaves its {@code plugin_<id>} schema behind, and
     * a plugin's objects are not part of the core baseline. (A pg_dump schema switch would change
     * how extensions are dumped, so they are filtered here instead.)
     */
    private static final Pattern NOISE =
            Pattern.compile("\\b(databasechangelog|databasechangeloglock)\\b|\\b(metric_sample|message_index)_\\d{8}"
                    + "|\\bSCHEMA plugin_|\\bplugin_[a-z0-9_]+\\.[a-z_]");

    static Set<String> statements(String dump) {
        String text = dump.replaceAll("(?m)^--.*$", "").replaceAll("(?m)^\\\\(restrict|unrestrict) .*$", "");
        Set<String> statements = new TreeSet<>();
        for (String statement : text.split(";\n")) {
            String s = String.join(" ", statement.trim().split("\\s+")).replaceAll("\\bpublic\\.", "");
            boolean dumpSetup = s.startsWith("SET ") || s.startsWith("SELECT pg_catalog.set_config");
            if (s.isEmpty()
                    || dumpSetup
                    || s.startsWith("COMMENT ON EXTENSION")
                    || s.contains(" OWNER TO ")
                    || NOISE.matcher(s).find()) {
                continue;
            }
            statements.add(s);
        }
        return statements;
    }

    @Test
    void theBaselineDiffersFromTheReferenceOnlyOnPurpose() throws Exception {
        var dump = POSTGRES.execInContainer(
                "pg_dump", "-U", POSTGRES.getUsername(), "-d", POSTGRES.getDatabaseName(), "--schema-only");
        assertThat(dump.getExitCode()).as(dump.getStderr()).isZero();

        Set<String> reference = statements(new ClassPathResource("db/reference-schema.sql").getContentAsString(UTF_8));
        Set<String> baseline = statements(dump.getStdout());
        Set<String> differences = new TreeSet<>();
        reference.stream().filter(s -> !baseline.contains(s)).map(s -> "- " + s).forEach(differences::add);
        baseline.stream().filter(s -> !reference.contains(s)).map(s -> "+ " + s).forEach(differences::add);

        assertThat(differences)
                .as("only deliberate differences")
                .allSatisfy(
                        d -> assertThat(DELIBERATE).anyMatch(p -> p.matcher(d).find()));
        assertThat(DELIBERATE)
                .as("every deliberate difference is present")
                .allSatisfy(
                        p -> assertThat(differences).anyMatch(d -> p.matcher(d).find()));
    }
}
