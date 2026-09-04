package io.github.sudoitir.artemisstudio.support;

import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * Base for tests that need a real Apache ActiveMQ Artemis broker — the Core
 * protocol client, {@code activemq.notifications}, and faithful message I/O all
 * need one, and nothing in the suite booted a broker before Phase 4.
 *
 * <p>The container is a process-wide singleton, started once in a {@code static}
 * block and never stopped (Ryuk cleans it up at JVM exit). Same reasoning as
 * {@link PostgresIntegrationTest}: Spring caches contexts across test classes, so
 * a per-class {@code @Container} that stops after each class would leave a later
 * class's cached context pointing at a dead broker.
 *
 * <p>The mounted {@code broker.xml} is the dev-compose primary fixture, which is
 * a complete config already carrying {@code NotificationActiveMQServerPlugin} and
 * the {@code activemq.notifications} {@code consume} + {@code createNonDurableQueue}
 * permissions. Its replication {@code ha-policy} and static cluster-connection
 * name a backup peer that is not present here; the broker still becomes live
 * (its {@code check-for-active-server} probe finds no active server) and the
 * unreachable cluster-connection only logs, it does not block startup.
 */
public abstract class ArtemisIntegrationTest {

    private static final String IMAGE = "apache/activemq-artemis:2.44.0";
    protected static final String BROKER_USER = "artemis";
    protected static final String BROKER_PASSWORD = "artemis";

    protected static final GenericContainer<?> ARTEMIS = new GenericContainer<>(IMAGE)
            .withEnv("ARTEMIS_USER", BROKER_USER)
            .withEnv("ARTEMIS_PASSWORD", BROKER_PASSWORD)
            .withEnv("ANONYMOUS_LOGIN", "false")
            .withCopyFileToContainer(
                    // The dev-compose primary fixture is the single source of truth for the
                    // broker.xml Studio expects; path is relative to the module root, the CWD
                    // during `mvn test`.
                    MountableFile.forHostPath("deploy/compose/artemis/primary/broker.xml"),
                    "/var/lib/artemis-instance/etc-override/broker.xml")
            .withExposedPorts(61616, 8161)
            .waitingFor(
                    Wait.forLogMessage(".*Artemis Console available.*", 1).withStartupTimeout(Duration.ofSeconds(120)))
            .withReuse(true);

    static {
        ARTEMIS.start();
    }

    /** Core protocol URL for the mapped acceptor port. */
    protected static String coreUrl() {
        return "tcp://%s:%d".formatted(ARTEMIS.getHost(), ARTEMIS.getMappedPort(61616));
    }

    /** Jolokia base URL for the mapped console port. */
    protected static String jolokiaUrl() {
        return "http://%s:%d/console/jolokia".formatted(ARTEMIS.getHost(), ARTEMIS.getMappedPort(8161));
    }
}
