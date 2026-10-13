package io.github.sudoitir.artemisstudio.kernel.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/** Two databases stand for the primary and its replica; the replica's lag is stubbed through the probe seam. */
class ReadRoutingTest {

    private static final Duration MAX_LAG = Duration.ofSeconds(30);

    private static final PostgreSQLContainer<?> PRIMARY = new PostgreSQLContainer<>("postgres:18-alpine");
    private static final PostgreSQLContainer<?> REPLICA = new PostgreSQLContainer<>("postgres:18-alpine");

    private static DataSource primary;
    private static DataSource replica;

    @BeforeAll
    static void startBoth() {
        PRIMARY.start();
        REPLICA.start();
        primary = source(PRIMARY);
        replica = source(REPLICA);
        new JdbcTemplate(primary).execute("CREATE TABLE seen (origin text)");
        new JdbcTemplate(primary).update("INSERT INTO seen VALUES ('primary')");
        new JdbcTemplate(replica).execute("CREATE TABLE seen (origin text)");
        new JdbcTemplate(replica).update("INSERT INTO seen VALUES ('replica')");
    }

    @AfterAll
    static void stopBoth() {
        REPLICA.stop();
        PRIMARY.stop();
    }

    private static DataSource source(PostgreSQLContainer<?> container) {
        return new DriverManagerDataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    private static String origin(org.springframework.jdbc.core.simple.JdbcClient client) {
        return client.sql("SELECT origin FROM seen").query(String.class).single();
    }

    private static ReadRouting routing(DataSource replicaSource, Supplier<Optional<Duration>> lag) {
        return new ReadRouting(primary, replicaSource, MAX_LAG, lag);
    }

    @Test
    void withoutAReplicaEveryReadUsesThePrimary() {
        ReadRouting routing = new ReadRouting(primary, null, MAX_LAG, Optional::empty);

        Routed<String> read = routing.read(ReadRoutingTest::origin);

        assertThat(routing.hasReplica()).isFalse();
        assertThat(read).isEqualTo(new Routed<>("primary", false));
    }

    @Test
    void aReplicaWithinTheBoundServesTheReadAndSaysSo() {
        ReadRouting routing = routing(replica, () -> Optional.of(Duration.ofSeconds(2)));

        assertThat(routing.read(ReadRoutingTest::origin)).isEqualTo(new Routed<>("replica", true));
    }

    @Test
    void aReplicaPastTheBoundOrOfUnknownLagLeavesTheReadToThePrimary() {
        assertThat(routing(replica, () -> Optional.of(Duration.ofSeconds(31))).read(ReadRoutingTest::origin))
                .isEqualTo(new Routed<>("primary", false));
        assertThat(routing(replica, Optional::empty).read(ReadRoutingTest::origin))
                .isEqualTo(new Routed<>("primary", false));
    }

    @Test
    void theLagIsCheckedOnceInTheCacheWindow() {
        AtomicInteger probes = new AtomicInteger();
        ReadRouting routing = routing(replica, () -> {
            probes.incrementAndGet();
            return Optional.of(Duration.ZERO);
        });

        routing.read(ReadRoutingTest::origin);
        routing.read(ReadRoutingTest::origin);

        assertThat(probes).hasValue(1);
    }

    @Test
    void aReplicaThatCannotBeReachedLeavesTheReadToThePrimary() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:postgresql://127.0.0.1:1/none");
        config.setUsername("x");
        config.setPassword("x");
        config.setConnectionTimeout(250);
        config.setInitializationFailTimeout(-1);
        try (HikariDataSource unreachable = new HikariDataSource(config)) {
            ReadRouting routing = routing(unreachable, () -> Optional.of(Duration.ZERO));

            assertThat(routing.read(ReadRoutingTest::origin)).isEqualTo(new Routed<>("primary", false));
        }
    }

    @Test
    void theCallbackRunsInAReadOnlyTransactionWhereverItRuns() {
        AtomicReference<String> mode = new AtomicReference<>();

        routing(replica, () -> Optional.of(Duration.ZERO))
                .read(client -> mode.getAndSet(client.sql("SHOW transaction_read_only")
                        .query(String.class)
                        .single()));
        assertThat(mode).hasValue("on");

        assertThatThrownBy(() -> new ReadRouting(primary, null, MAX_LAG, Optional::empty)
                        .read(client ->
                                client.sql("INSERT INTO seen VALUES ('x')").update()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("read-only transaction");
    }
}
