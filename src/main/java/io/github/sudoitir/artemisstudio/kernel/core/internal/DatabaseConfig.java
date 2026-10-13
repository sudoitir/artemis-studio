package io.github.sudoitir.artemisstudio.kernel.core.internal;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.sudoitir.artemisstudio.kernel.core.ReadRouting;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The database wiring that is not the primary pool: the preflight that runs before Liquibase, and
 * the optional read replica (ADR-0199).
 */
@Configuration(proxyBeanMethods = false)
class DatabaseConfig {

    /** The bean name Liquibase is made to depend on. */
    static final String PREFLIGHT = "databasePreflight";

    /**
     * Makes every {@code SpringLiquibase} wait for {@link DatabasePreflight}, so a database Studio
     * cannot run on fails the boot before any table is created.
     */
    @Bean
    static BeanFactoryPostProcessor liquibaseAfterPreflight() {
        return (ConfigurableListableBeanFactory beanFactory) -> {
            for (String name : beanFactory.getBeanNamesForType(SpringLiquibase.class, true, false)) {
                BeanDefinition definition = beanFactory.getBeanDefinition(name);
                String[] dependsOn = definition.getDependsOn();
                String[] extended = dependsOn == null ? new String[1] : Arrays.copyOf(dependsOn, dependsOn.length + 1);
                extended[extended.length - 1] = PREFLIGHT;
                definition.setDependsOn(extended);
            }
        };
    }

    @Bean(PREFLIGHT)
    DatabasePreflight databasePreflight(DataSource dataSource) {
        return new DatabasePreflight(dataSource);
    }

    @Bean
    ReadRouting readRouting(DataSource primary, DatabaseReadProperties read) {
        if (!read.configured()) {
            return new ReadRouting(primary, null, read.maxLag(), Optional::empty);
        }
        HikariDataSource replica = readPool(primary, read);
        return new ReadRouting(primary, replica, read.maxLag(), () -> replicaLag(replica));
    }

    /**
     * The primary's pool with the replica's address: the same timeouts and per-connection limits, so a
     * replica that stalls fails as fast as the primary would. It starts without waiting for the
     * replica, which may be down when Studio boots; reads then fall back to the primary.
     */
    private static HikariDataSource readPool(DataSource primary, DatabaseReadProperties read) {
        HikariConfig config = new HikariConfig();
        try {
            primary.unwrap(HikariDataSource.class).copyStateTo(config);
        } catch (SQLException e) {
            throw new IllegalStateException("The primary datasource is not a Hikari pool", e);
        }
        config.setPoolName("studio-read");
        config.setJdbcUrl(read.url());
        if (!read.username().isBlank()) {
            config.setUsername(read.username());
            config.setPassword(read.password());
        }
        config.setMaximumPoolSize(read.poolSize());
        config.setMinimumIdle(read.poolSize());
        config.setReadOnly(true);
        config.setInitializationFailTimeout(-1);
        return new HikariDataSource(config);
    }

    private static Optional<Duration> replicaLag(DataSource replica) {
        try {
            Double seconds = new JdbcTemplate(replica).queryForObject("""
                    SELECT CASE WHEN pg_is_in_recovery()
                                THEN extract(epoch FROM now() - pg_last_xact_replay_timestamp())
                                ELSE 0 END
                    """, Double.class);
            return seconds == null ? Optional.empty() : Optional.of(Duration.ofMillis((long) (seconds * 1000)));
        } catch (DataAccessException _) {
            return Optional.empty();
        }
    }
}
