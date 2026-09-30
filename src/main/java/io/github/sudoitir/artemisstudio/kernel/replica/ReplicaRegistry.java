package io.github.sudoitir.artemisstudio.kernel.replica;

import io.github.sudoitir.artemisstudio.kernel.core.ShutdownPhases;
import java.net.InetAddress;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * This process as one replica of the installation (ADR-0152). It mints an identity, records itself
 * in {@code studio_replica}, and a dedicated platform thread renews a heartbeat taken from database
 * time, so clock skew between replicas does not matter. The thread is not on the job pool: a
 * starved pool must not make a healthy replica look dead.
 *
 * <p>The state lives in memory and the heartbeat writes it, so a transition whose own write failed
 * reaches the table with the next beat. A replica that ends without {@link #markStopped} has no
 * {@code stopped_at}, and once its heartbeat is older than the ttl it is a crash.
 */
@Component
@Slf4j
public class ReplicaRegistry implements SmartLifecycle {

    public enum State {
        STARTING,
        READY,
        DRAINING,
        STOPPED;

        String column() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** One row of the registry. */
    public record Replica(UUID id, String host, String version, State state, Instant startedAt, Instant heartbeatAt) {}

    /**
     * A replica as the health view shows it: how long ago it last checked in, by database time, and
     * whether it is gone (it never recorded a stop, and its heartbeat is older than the ttl).
     */
    public record Seen(Replica replica, long heartbeatAgeMillis, boolean gone) {}

    private final UUID id = UUID.randomUUID();
    private final JdbcTemplate jdbc;
    private final HaProperties ha;
    private final String host = hostName();
    private final String version;

    private volatile State state = State.STARTING;
    private volatile Thread heartbeat;

    public ReplicaRegistry(JdbcTemplate jdbc, HaProperties ha, ObjectProvider<BuildProperties> build) {
        this.jdbc = jdbc;
        this.ha = ha;
        BuildProperties built = build.getIfAvailable();
        this.version = built == null ? "unknown" : built.getVersion();
    }

    /** This process's identity, minted when it started. */
    public UUID id() {
        return id;
    }

    public State state() {
        return state;
    }

    /** Starting to ready, once the plugin boot sequence has finished. Anything else is left alone. */
    public void markReady() {
        transition(State.READY, State.STARTING);
    }

    /** A replica that is shutting down stays up for the load balancer's sake but asks for no more traffic. */
    public void markDraining() {
        transition(State.DRAINING, State.STARTING, State.READY);
    }

    /** Ends the heartbeat and records the clean stop, so this replica is never counted as a crash. */
    public void markStopped() {
        stopHeartbeat();
        state = State.STOPPED;
        try {
            jdbc.update("UPDATE studio_replica SET state = 'stopped', stopped_at = now() WHERE id = ?", id);
        } catch (DataAccessException e) {
            log.warn("Could not record that replica {} stopped; it will count as a crash: {}", id, e.getMessage());
        }
    }

    /** The replicas that are starting or ready and whose heartbeat is younger than the ttl. */
    public List<Replica> live() {
        return jdbc.query("""
                SELECT id, host, version, state, started_at, heartbeat_at FROM studio_replica
                WHERE stopped_at IS NULL AND state IN ('starting', 'ready')
                  AND heartbeat_at > now() - make_interval(secs => ?)
                ORDER BY started_at, id
                """, (rs, i) -> replica(rs), seconds(ha.ttl()));
    }

    /** Every replica whose last heartbeat is within {@code window}, including stopped and gone ones, oldest first. */
    public List<Seen> seen(Duration window) {
        return jdbc.query(
                """
                SELECT id, host, version, state, started_at, heartbeat_at,
                       (extract(epoch FROM now() - heartbeat_at) * 1000)::bigint AS age_millis,
                       (stopped_at IS NULL AND heartbeat_at <= now() - make_interval(secs => ?)) AS gone
                FROM studio_replica
                WHERE heartbeat_at > now() - make_interval(secs => ?)
                ORDER BY started_at, id
                """,
                (rs, i) -> new Seen(replica(rs), rs.getLong("age_millis"), rs.getBoolean("gone")),
                seconds(ha.ttl()),
                seconds(window));
    }

    private static Replica replica(ResultSet rs) throws SQLException {
        return new Replica(
                rs.getObject("id", UUID.class),
                rs.getString("host"),
                rs.getString("version"),
                State.valueOf(rs.getString("state").toUpperCase(Locale.ROOT)),
                rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("heartbeat_at").toInstant());
    }

    /**
     * The replicas that have not stopped and whose heartbeat is younger than the ttl, draining ones
     * included: a draining replica is still finishing the runs it holds. A run whose replica is not here
     * is orphaned. A {@code HashSet}, so a run with no replica (a null) asks {@code contains} safely.
     */
    public Set<UUID> aliveIds() {
        return new HashSet<>(jdbc.queryForList("""
                SELECT id FROM studio_replica
                WHERE stopped_at IS NULL AND heartbeat_at > now() - make_interval(secs => ?)
                """, UUID.class, seconds(ha.ttl())));
    }

    /** Replicas that ended without recording a stop, whose last heartbeat fell within {@code window}. */
    public long crashesSince(Duration window) {
        Long crashes = jdbc.queryForObject("""
                SELECT count(*) FROM studio_replica
                WHERE stopped_at IS NULL
                  AND heartbeat_at < now() - make_interval(secs => ?)
                  AND heartbeat_at > now() - make_interval(secs => ?)
                """, Long.class, seconds(ha.ttl()), seconds(window));
        return crashes == null ? 0 : crashes;
    }

    // ---- lifecycle: registered first on start, recorded stopped last on stop ----------------------

    @Override
    public void start() {
        if (heartbeat != null) {
            return;
        }
        state = State.STARTING;
        jdbc.update("""
                INSERT INTO studio_replica (started_at, heartbeat_at, host, version, state, id)
                VALUES (now(), now(), ?, ?, 'starting', ?)
                ON CONFLICT (id) DO UPDATE
                SET started_at = now(), heartbeat_at = now(), stopped_at = NULL, state = 'starting'
                """, host, version, id);
        Thread thread = new Thread(this::beat, "studio-replica-heartbeat");
        thread.setDaemon(true);
        heartbeat = thread;
        thread.start();
        log.info("Replica {} on {} registered, version {}", id, host, version);
    }

    @Override
    public void stop() {
        if (heartbeat != null) {
            markStopped();
        }
    }

    @Override
    public boolean isRunning() {
        return heartbeat != null;
    }

    /** Stops last, so the shutdown of everything else is still covered by the heartbeat. */
    @Override
    public int getPhase() {
        return ShutdownPhases.REPLICA;
    }

    private void beat() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(ha.heartbeat());
                jdbc.update(
                        "UPDATE studio_replica SET heartbeat_at = now(), state = ? WHERE id = ? AND stopped_at IS NULL",
                        state.column(),
                        id);
            } catch (InterruptedException _) {
                return;
            } catch (RuntimeException e) {
                log.warn("Replica {} could not record its heartbeat: {}", id, e.getMessage());
            }
        }
    }

    private void stopHeartbeat() {
        Thread thread = heartbeat;
        heartbeat = null;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(Duration.ofSeconds(2));
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void transition(State to, State... from) {
        if (!List.of(from).contains(state)) {
            return;
        }
        state = to;
        try {
            jdbc.update("UPDATE studio_replica SET state = ?, heartbeat_at = now() WHERE id = ?", to.column(), id);
        } catch (DataAccessException e) {
            log.warn("Could not record replica {} as {}; the next heartbeat will: {}", id, to.column(), e.getMessage());
        }
    }

    private static double seconds(Duration duration) {
        return duration.toMillis() / 1000.0;
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (java.io.IOException _) {
            return "unknown";
        }
    }
}
