package io.github.sudoitir.artemisstudio.support;

import io.github.sudoitir.artemisstudio.platform.scrape.ScrapeScheduler;
import java.util.List;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for tests that need the full context against a real PostgreSQL: Liquibase
 * applies the changelog, Hibernate validates the mapping against it, and the
 * {@code SecretVault} key is supplied so the context starts.
 *
 * <p>The container is a process-wide singleton, started once and never stopped
 * (Ryuk cleans it up at JVM exit). This is the CI-safe Testcontainers pattern:
 * Spring caches contexts across test classes, so a per-class {@code @Container}
 * that stops after each class leaves a later class's cached context pointing at a
 * dead database.
 */
@SpringBootTest
public abstract class PostgresIntegrationTest {

    /**
     * The scheduled scrape tiers would otherwise run inside the shared context and call
     * whichever {@code BrokerConnections} a test has mocked, consuming the canned Jolokia
     * responses that test queued for its own requests. A mock configures no tasks, so no
     * tier is scheduled; no test relies on background scraping, and
     * {@code ScrapeSchedulerTest} drives the tiers directly.
     */
    @MockitoBean
    ScrapeScheduler scrapeScheduler;

    /**
     * Spring keeps every distinct test context cached, each with its own connection pool, so the
     * default 100 connections run out once enough test configurations exist.
     */
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18-alpine").withCommand("postgres", "-c", "max_connections=400");

    /** Base64 of exactly 32 bytes. */
    private static final String SECRET_KEY = "YXJ0ZW1pcy1zdHVkaW8tdGVzdC1rZXktMzJieXRlcyE=";

    static {
        POSTGRES.withReuse(true).start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("artemis-studio.secret-key", () -> SECRET_KEY);
    }

    /** What an application started outside the Spring test framework needs to use the shared database. */
    public static List<String> connectionProperties() {
        return List.of(
                "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "spring.datasource.username=" + POSTGRES.getUsername(),
                "spring.datasource.password=" + POSTGRES.getPassword(),
                "artemis-studio.secret-key=" + SECRET_KEY);
    }
}
