package io.github.sudoitir.artemisstudio;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Smoke test: the Spring context loads and Liquibase applies the full changelog
 * (through {@code 008-node-ha-state.sql}) cleanly against a real PostgreSQL. If
 * this stays green, the schema and wiring are sound.
 */
@SpringBootTest
@Testcontainers
class ArtemisStudioApplicationTests {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // base64 of 32 bytes — SecretVault refuses to start without it.
        registry.add("artemis-studio.secret-key", () -> "YXJ0ZW1pcy1zdHVkaW8tdGVzdC1rZXktMzJieXRlcyE=");
    }

    @Test
    void contextLoads() {}
}
