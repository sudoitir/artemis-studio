package io.github.sudoitir.rahdar.kernel.core.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.rahdar.RahdarApplication;
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
 * Rahdar on a database it does not administer (ADR-0199): a role that is not a superuser, owns one
 * schema other than {@code public} and may create schemas in the database. Rahdar migrates into that
 * schema alone, and the boot refuses a role that cannot.
 */
class ExternalDatabaseIntegrationTest {

    private static final String ROLE = "rahdar_ext";
    private static final String SCHEMA = "rahdar_ext";

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

    private static ConfigurableApplicationContext rahdar(String role, String password, String schema) {
        return new SpringApplicationBuilder(RahdarApplication.class)
                .run(
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl() + "&currentSchema=" + schema,
                        "--spring.datasource.username=" + role,
                        "--spring.datasource.password=" + password,
                        "--rahdar.secret-key=" + SECRET_KEY,
                        "--server.port=0",
                        "--rahdar.ha.drain-delay=0s",
                        "--rahdar.ha.run-grace=0s");
    }

    @Test
    void rahdarMigratesAndBootsInItsOwnSchemaAsANonSuperuser() {
        try (ConfigurableApplicationContext rahdar = rahdar(ROLE, "ext", SCHEMA)) {
            List<String> schemasWithTables = admin.queryForList("""
                    SELECT DISTINCT n.nspname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE c.relkind IN ('r', 'p') AND n.nspname NOT IN ('pg_catalog', 'information_schema')
                      AND n.nspname NOT LIKE 'pg\\_%'
                    """, String.class);
            assertThat(schemasWithTables).containsExactly(SCHEMA);
            assertThat(admin.queryForObject("SELECT count(*) FROM " + SCHEMA + ".databasechangelog", Integer.class))
                    .isPositive();
            assertThat(rahdar.getBean(JdbcTemplate.class).queryForObject("SELECT current_schema()", String.class))
                    .isEqualTo(SCHEMA);
        }
    }

    @Test
    void theBootRefusesARoleThatCannotCreateInItsSchemaAndSaysHowToFixIt() {
        admin.execute("CREATE ROLE rahdar_readonly LOGIN PASSWORD 'ro' NOSUPERUSER");
        admin.execute("CREATE SCHEMA rahdar_locked");
        admin.execute("GRANT USAGE ON SCHEMA rahdar_locked TO rahdar_readonly");

        assertThatThrownBy(() -> rahdar("rahdar_readonly", "ro", "rahdar_locked"))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause()
                .hasMessageContaining("GRANT CREATE ON DATABASE " + POSTGRES.getDatabaseName() + " TO rahdar_readonly")
                .hasMessageContaining("GRANT USAGE, CREATE ON SCHEMA rahdar_locked TO rahdar_readonly");
    }
}
