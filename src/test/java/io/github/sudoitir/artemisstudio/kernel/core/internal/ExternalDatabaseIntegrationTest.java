package io.github.sudoitir.artemisstudio.kernel.core.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.artemisstudio.ArtemisStudioApplication;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Studio on a database it does not administer (ADR-0199): a role that is not a superuser, owns one
 * schema other than {@code public} and may create schemas in the database. Studio migrates into that
 * schema alone, and the boot refuses a role that cannot.
 */
class ExternalDatabaseIntegrationTest {

    private static final String ROLE = "studio_ext";
    private static final String SCHEMA = "studio_ext";

    /** Base64 of exactly 32 bytes. */
    private static final String SECRET_KEY = "YXJ0ZW1pcy1zdHVkaW8tdGVzdC1rZXktMzJieXRlcyE=";

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    private static JdbcTemplate admin;

    @BeforeAll
    static void createTheRoleAndItsSchema() {
        POSTGRES.start();
        admin = new JdbcTemplate(
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        admin.execute("CREATE ROLE " + ROLE + " LOGIN PASSWORD 'ext' NOSUPERUSER NOCREATEDB NOCREATEROLE");
        admin.execute("GRANT CREATE ON DATABASE " + POSTGRES.getDatabaseName() + " TO " + ROLE);
        admin.execute("CREATE SCHEMA " + SCHEMA + " AUTHORIZATION " + ROLE);
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    private static ConfigurableApplicationContext studio(String role, String password, String schema) {
        return new SpringApplicationBuilder(ArtemisStudioApplication.class)
                .run(
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl() + "&currentSchema=" + schema,
                        "--spring.datasource.username=" + role,
                        "--spring.datasource.password=" + password,
                        "--artemis-studio.secret-key=" + SECRET_KEY,
                        "--server.port=0",
                        "--artemis-studio.ha.drain-delay=0s",
                        "--artemis-studio.ha.run-grace=0s");
    }

    @Test
    void studioMigratesAndBootsInItsOwnSchemaAsANonSuperuser() {
        try (ConfigurableApplicationContext studio = studio(ROLE, "ext", SCHEMA)) {
            List<String> schemasWithTables = admin.queryForList("""
                    SELECT DISTINCT n.nspname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE c.relkind IN ('r', 'p') AND n.nspname NOT IN ('pg_catalog', 'information_schema')
                      AND n.nspname NOT LIKE 'pg\\_%'
                    """, String.class);
            assertThat(schemasWithTables).containsExactly(SCHEMA);
            assertThat(admin.queryForObject("SELECT count(*) FROM " + SCHEMA + ".databasechangelog", Integer.class))
                    .isPositive();
            assertThat(studio.getBean(JdbcTemplate.class).queryForObject("SELECT current_schema()", String.class))
                    .isEqualTo(SCHEMA);
        }
    }

    @Test
    void theBootRefusesARoleThatCannotCreateInItsSchemaAndSaysHowToFixIt() {
        admin.execute("CREATE ROLE studio_readonly LOGIN PASSWORD 'ro' NOSUPERUSER");
        admin.execute("CREATE SCHEMA studio_locked");
        admin.execute("GRANT USAGE ON SCHEMA studio_locked TO studio_readonly");

        assertThatThrownBy(() -> studio("studio_readonly", "ro", "studio_locked"))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause()
                .hasMessageContaining("GRANT CREATE ON DATABASE " + POSTGRES.getDatabaseName() + " TO studio_readonly")
                .hasMessageContaining("GRANT USAGE, CREATE ON SCHEMA studio_locked TO studio_readonly");
    }
}
