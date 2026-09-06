package io.github.sudoitir.artemisstudio.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

/**
 * The bootstrap-phase loader: what it reads, how profiles layer, and — the part
 * that matters most — that it never stops the application from starting.
 */
class JdbcConfigPropertySourceLocatorTest extends PostgresIntegrationTest {

    private final JdbcConfigPropertySourceLocator locator = new JdbcConfigPropertySourceLocator();

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ConfigurableEnvironment springEnvironment;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM studio_config_property");
    }

    private MockEnvironment environment(String... activeProfiles) {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.application.name", "artemis-studio");
        env.setProperty("spring.datasource.url", springEnvironment.getProperty("spring.datasource.url"));
        env.setProperty("spring.datasource.username", springEnvironment.getProperty("spring.datasource.username"));
        env.setProperty("spring.datasource.password", springEnvironment.getProperty("spring.datasource.password"));
        env.setActiveProfiles(activeProfiles);
        return env;
    }

    private void row(String profile, String key, String value) {
        jdbc.update(
                "INSERT INTO studio_config_property (application, profile, label, \"key\", \"value\") "
                        + "VALUES (?, ?, 'master', ?, ?)",
                "artemis-studio",
                profile,
                key,
                value);
    }

    @Test
    void loadsPropertiesForTheApplication() {
        row("default", "artemis-studio.branding.product-name", "Broker Console");

        PropertySource<?> source = locator.locate(environment());

        assertThat(source).isNotNull();
        assertThat(source.getProperty("artemis-studio.branding.product-name")).isEqualTo("Broker Console");
    }

    /** A profile row beats the shared one — that is the whole reason profiles are in the key. */
    @Test
    void aProfileRowOverridesTheDefaultRow() {
        row("default", "artemis-studio.scrape.tier-a-interval", "5s");
        row("prod", "artemis-studio.scrape.tier-a-interval", "30s");

        assertThat(locator.locate(environment("prod")).getProperty("artemis-studio.scrape.tier-a-interval"))
                .isEqualTo("30s");
        assertThat(locator.locate(environment()).getProperty("artemis-studio.scrape.tier-a-interval"))
                .isEqualTo("5s");
    }

    /**
     * Without these three flags Spring Cloud inserts a bootstrap source above
     * everything, environment variables included — so a row here would beat the
     * container's own settings and could not be corrected from outside the database.
     * They have to travel in the source itself, because
     * {@code PropertySourceBootstrapConfiguration} binds them from the incoming
     * remote source and not from local configuration; set in {@code bootstrap.yml}
     * they are read by nobody. Verified end to end: with them, an environment
     * variable beats a stored row, and a stored row still beats {@code application.yml}.
     */
    @Test
    void theSourceCarriesTheFlagsThatKeepItBelowTheEnvironment() {
        row("default", "artemis-studio.branding.product-name", "Broker Console");

        PropertySource<?> source = locator.locate(environment());

        assertThat(source.getProperty("spring.cloud.config.allowOverride")).isEqualTo("true");
        assertThat(source.getProperty("spring.cloud.config.overrideNone")).isEqualTo("false");
        assertThat(source.getProperty("spring.cloud.config.overrideSystemProperties"))
                .isEqualTo("false");
    }

    @Test
    void contributesNothingWhenThereAreNoRows() {
        assertThat(locator.locate(environment())).isNull();
    }

    /**
     * The one that keeps a deployment alive: on a first start the schema does not
     * exist yet, because Liquibase has not run and cannot run until the main context
     * is up. If this threw, an empty database would brick the very start that was
     * about to create the table.
     */
    @Test
    void aMissingTableIsAWarningAndNotAStartupFailure() {
        MockEnvironment env = environment();
        env.setProperty("spring.datasource.url", "jdbc:postgresql://127.0.0.1:1/nope");

        assertThatCode(() -> assertThat(locator.locate(env)).isNull()).doesNotThrowAnyException();
    }

    /** No datasource configured at all is simply "nothing to load", not an error. */
    @Test
    void noDatasourceUrlContributesNothing() {
        MockEnvironment env = new MockEnvironment();

        assertThat(locator.locate(env)).isNull();
    }
}
