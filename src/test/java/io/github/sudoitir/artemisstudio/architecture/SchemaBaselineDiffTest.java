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
                    "flow_(demand|client_edge|node_sample)")
            .map(Pattern::compile)
            .toList();

    /** Liquibase's own tables, and the daily partitions created at runtime. */
    private static final Pattern NOISE =
            Pattern.compile("\\b(databasechangelog|databasechangeloglock)\\b|\\b(metric_sample|message_index)_\\d{8}");

    static Set<String> statements(String dump) {
        String text = dump.replaceAll("(?m)^--.*$", "").replaceAll("(?m)^\\\\(restrict|unrestrict) .*$", "");
        Set<String> statements = new TreeSet<>();
        for (String statement : text.split(";\n")) {
            String s = String.join(" ", statement.trim().split("\\s+")).replace("public.", "");
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
