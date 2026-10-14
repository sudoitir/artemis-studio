package io.github.sudoitir.rahdar.platform.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.sun.net.httpserver.HttpServer;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ssl.SslBundles;
import tools.jackson.databind.json.JsonMapper;

/**
 * Regression guard for the field OOM: every Jolokia call used to build a new JDK
 * {@code HttpClient}, each holding a selector thread and an epoll descriptor until a GC
 * happened to collect it. Clients must be shared, so thread count does not track call count.
 */
class BrokerClientFactoryTest {

    private static final String URL = "http://127.0.0.1:1/console/jolokia";
    private static final BrokerConnectionSettings SETTINGS =
            BrokerConnectionSettings.basicAuth(UUID.randomUUID(), "admin", "admin");

    private final BrokerClientFactory factory = new BrokerClientFactory(
            JsonMapper.builder().build(),
            mock(SslBundles.class),
            new BrokerProperties(Duration.ofSeconds(3), Duration.ofSeconds(10), 2_000),
            new ClockOffsetRegistry(Clock.systemUTC()),
            new NodeCallHealth(),
            new NodeCallLimiter(
                    new RateLimitProperties(1_000), new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
            io.micrometer.observation.ObservationRegistry.NOOP,
            BrokerCallGuard.OFF);

    @AfterEach
    void tearDown() {
        factory.destroy();
    }

    @Test
    void buildingClientsDoesNotGrowThreads() {
        calls(20);
        int before = liveThreads();

        calls(200);

        assertThat(liveThreads() - before).isLessThan(10);
    }

    @Test
    void changingTimeoutsDoesNotGrowThreads() {
        calls(20);
        int before = liveThreads();

        for (int i = 0; i < 20; i++) {
            factory.setTimeouts(Duration.ofSeconds(3 + i % 2), Duration.ofSeconds(10));
            calls(10);
        }

        // Old clients are shut down asynchronously; allow their selector threads to exit.
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (liveThreads() - before >= 10 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(liveThreads() - before).isLessThan(10);
    }

    @Test
    void redirectIsStillNotFollowedAfterATimeoutChange() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Location", "/hawtio/login");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        try {
            factory.setTimeouts(Duration.ofSeconds(2), Duration.ofSeconds(5));
            JolokiaBrokerClient client = factory.forNode(
                    SETTINGS, "http://127.0.0.1:" + server.getAddress().getPort() + "/console");

            JolokiaRequest request = JolokiaRequest.search("org.apache.activemq.artemis:*");
            Assertions.assertThatThrownBy(() -> client.single(request))
                    .isInstanceOf(BrokerConnectionException.class)
                    .hasMessageContaining("redirected");
        } finally {
            server.stop(0);
        }
    }

    private void calls(int n) {
        for (int i = 0; i < n; i++) {
            factory.forNode(SETTINGS, URL);
        }
    }

    private static int liveThreads() {
        return ManagementFactory.getThreadMXBean().getThreadCount();
    }
}
