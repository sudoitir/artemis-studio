package io.github.sudoitir.artemisstudio;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for Artemis Studio — a cluster-wide management and observability
 * console for Apache ActiveMQ Artemis.
 *
 * <p>Product layers 0–8 are in place: the Jolokia broker client and capability
 * probe, cluster registration and topology discovery, HA / split-brain
 * detection, cross-node views over SSE, message operations with audit, the Core
 * client and request-reply tracing, metrics and charts, alerting, and
 * governance (auth, RBAC, environments). Remaining work is the Roadmap in
 * {@code README.md}; the living specs are under {@code openspec/}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class ArtemisStudioApplication {

    public static void main(String[] args) {
        SpringApplication.run(ArtemisStudioApplication.class, args);
    }
}
