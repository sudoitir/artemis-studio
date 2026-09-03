package io.github.sudoitir.artemisstudio.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        POSTGRES.withReuse(true).start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // base64 of exactly 32 bytes.
        registry.add("artemis-studio.secret-key", () -> "YXJ0ZW1pcy1zdHVkaW8tdGVzdC1rZXktMzJieXRlcyE=");
    }
}
