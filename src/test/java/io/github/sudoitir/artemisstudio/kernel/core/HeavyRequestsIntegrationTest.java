package io.github.sudoitir.artemisstudio.kernel.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Two replicas are two {@link HeavyRequests} on one database, each with its own identity: the permits
 * are rows, so the limits hold for both together (ADR-0196).
 */
class HeavyRequestsIntegrationTest extends PostgresIntegrationTest {

    private static final HeavyRequests.Endpoint EXPORTS =
            new HeavyRequests.Endpoint("exports", 2, Duration.ofSeconds(30), 3);

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    private final List<HeavyRequests.Run> open = new ArrayList<>();

    private HeavyRequests.Run open(HeavyRequests replica, String owner) {
        HeavyRequests.Run run = replica.open(EXPORTS, owner);
        open.add(run);
        return run;
    }

    @AfterEach
    void releaseEverything() {
        open.forEach(HeavyRequests.Run::close);
        jdbc.update("DELETE FROM heavy_permit");
    }

    @Test
    void aUsersLimitHoldsAcrossReplicas() {
        HeavyRequests a = new HeavyRequests(jdbc, dataSource);
        HeavyRequests b = new HeavyRequests(jdbc, dataSource);
        String user = "u-" + UUID.randomUUID();

        open(a, user);
        open(b, user);

        assertThatThrownBy(() -> open(a, user)).isInstanceOfSatisfying(HeavyLimitException.class, e -> {
            assertThat(e.installation()).isFalse();
            assertThat(e.cap()).isEqualTo(2);
            assertThat(e.getMessage()).contains("exports");
        });
        assertThatThrownBy(() -> open(b, user)).isInstanceOf(HeavyLimitException.class);
        assertThat(open(b, "other-" + UUID.randomUUID()))
                .as("another user is unaffected")
                .isNotNull();
    }

    @Test
    void closingAPermitFreesItsSlot() {
        HeavyRequests a = new HeavyRequests(jdbc, dataSource);
        HeavyRequests b = new HeavyRequests(jdbc, dataSource);
        String user = "u-" + UUID.randomUUID();
        HeavyRequests.Run first = open(a, user);
        open(b, user);

        first.close();

        assertThat(open(b, user)).isNotNull();
    }

    @Test
    void aRunThatThrowsStillReleasesItsPermit() {
        HeavyRequests a = new HeavyRequests(jdbc, dataSource);
        String user = "u-" + UUID.randomUUID();

        assertThatThrownBy(() -> a.run(EXPORTS, user, run -> {
                    throw new IllegalStateException("boom");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM heavy_permit WHERE owner = ?", Long.class, user))
                .isZero();
    }

    @Test
    void aReplicaThatDiesLosesItsPermitsAfterTheLease() {
        HeavyRequests dead = new HeavyRequests(jdbc, dataSource);
        HeavyRequests alive = new HeavyRequests(jdbc, dataSource);
        String user = "u-" + UUID.randomUUID();
        open(dead, user);
        open(dead, user);
        assertThatThrownBy(() -> open(alive, user)).isInstanceOf(HeavyLimitException.class);

        // The replica never releases; its leases run out.
        jdbc.update("UPDATE heavy_permit SET expires_at = now() - interval '1 second' WHERE owner = ?", user);

        assertThat(open(alive, user)).isNotNull();
        assertThat(open(alive, user)).isNotNull();
    }

    @Test
    void theInstallationCapHoldsWhoeverAsks() {
        HeavyRequests a = new HeavyRequests(jdbc, dataSource);
        HeavyRequests b = new HeavyRequests(jdbc, dataSource);
        HeavyRequests.Endpoint wide = new HeavyRequests.Endpoint("exports", 100, Duration.ofSeconds(30), 3);
        for (int i = 0; i < a.installationCap(); i++) {
            open.add((i % 2 == 0 ? a : b).open(wide, "user-" + i));
        }

        assertThatThrownBy(() -> b.open(wide, "one-more"))
                .isInstanceOfSatisfying(
                        HeavyLimitException.class,
                        e -> assertThat(e.installation()).isTrue());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM heavy_permit WHERE owner = 'one-more'", Long.class))
                .as("a refused request keeps no permit of its own")
                .isZero();
    }

    @Test
    void theInstallationCapIsAQuarterOfThePool() throws SQLException {
        int pool = dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize();

        assertThat(new HeavyRequests(jdbc, dataSource).installationCap()).isEqualTo(Math.max(1, pool / 4));
    }

    @Test
    void theRowCapStopsTheRunAndNamesTheBound() {
        HeavyRequests a = new HeavyRequests(jdbc, dataSource);

        try (HeavyRequests.Run run = open(a, "u-" + UUID.randomUUID())) {
            assertThat(run.admit()).isTrue();
            assertThat(run.admit()).isTrue();
            assertThat(run.admit()).isTrue();
            assertThat(run.truncated())
                    .as("three rows of three held is complete")
                    .isFalse();

            assertThat(run.admit()).isFalse();
            assertThat(run.reached()).isEqualTo(HeavyRequests.Bound.ROWS);
            assertThat(run.truncated()).isTrue();
        }
    }

    @Test
    void theTimeBoundStopsTheRunAndNamesTheBound() {
        HeavyRequests a = new HeavyRequests(jdbc, dataSource);
        HeavyRequests.Endpoint instant = new HeavyRequests.Endpoint("exports", 1, Duration.ZERO, 10);

        try (HeavyRequests.Run run = a.open(instant, "u-" + UUID.randomUUID())) {
            assertThat(run.admit()).isFalse();
            assertThat(run.reached()).isEqualTo(HeavyRequests.Bound.TIME);
        }
    }
}
