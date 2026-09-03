package io.github.sudoitir.artemisstudio.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base for tests that need the full context against a real PostgreSQL: Liquibase
 * applies the changelog, Hibernate validates the mapping against it, and the
 * {@code SecretVault} key is supplied so the context starts.
 */
@SpringBootTest
@Testcontainers
public abstract class PostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void secretKey(DynamicPropertyRegistry registry) {
        // base64 of exactly 32 bytes.
        registry.add("artemis-studio.secret-key", () -> "YXJ0ZW1pcy1zdHVkaW8tdGVzdC1rZXktMzJieXRlcyE=");
    }
}
