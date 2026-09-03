package io.github.sudoitir.artemisstudio;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for Artemis Studio — a cluster-wide management and observability
 * console for Apache ActiveMQ Artemis.
 *
 * <p>Phase 1 wires the first product layer: the Jolokia broker client, the
 * capability probe, cluster registration from seed URLs, topology discovery, and
 * HA / split-brain detection. See {@code docs/roadmap.md} and the
 * {@code openspec/} change {@code phase-1-connectivity-and-topology}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class ArtemisStudioApplication {

    public static void main(String[] args) {
        SpringApplication.run(ArtemisStudioApplication.class, args);
    }
}
