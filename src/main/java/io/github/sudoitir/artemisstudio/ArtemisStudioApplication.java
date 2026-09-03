package io.github.sudoitir.artemisstudio;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for Artemis Studio — a cluster-wide management and observability
 * console for Apache ActiveMQ Artemis.
 *
 * <p>This is the workspace skeleton: it starts, exposes {@code /actuator/health},
 * and serves the built React shell from {@code classpath:/static}. No product
 * features are wired yet — see {@code docs/roadmap.md}.
 */
@SpringBootApplication
public class ArtemisStudioApplication {

    public static void main(String[] args) {
        SpringApplication.run(ArtemisStudioApplication.class, args);
    }
}
