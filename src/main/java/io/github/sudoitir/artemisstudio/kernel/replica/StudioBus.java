package io.github.sudoitir.artemisstudio.kernel.replica;

import io.github.sudoitir.artemisstudio.kernel.core.ShutdownPhases;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * The bus the replicas talk over (ADR-0152): PostgreSQL {@code LISTEN}/{@code NOTIFY} on channel
 * {@code studio}. One dedicated connection listens, opened from the {@code spring.datasource}
 * values rather than taken from the pool, because it stays open for as long as the process runs.
 * A {@code studio-bus} thread reads notifications and pings the connection, and reconnects with a
 * backoff when it is lost.
 *
 * <p>{@link #publish} runs {@code pg_notify} through the caller's transaction, so a message is sent
 * only when that transaction commits and never when it rolls back. Every replica, the sender
 * included, receives the message on its listening connection and handles it there, so there is
 * one path for all of them: a {@link BusFrame}, {@link BusEvents} or {@link ReplicaSignal} is
 * published as a Spring event on the {@code studio-bus} thread, where a listener must not block.
 * After a reconnect a {@link BusResumed} is published first.
 *
 * <p>PostgreSQL caps a notification payload at 8,000 bytes, so a frame beyond {@value #MAX_PAYLOAD}
 * bytes is sent without its data, and a listener reloads what it needs.
 */
@Component
@Slf4j
public class StudioBus implements SmartLifecycle {

    static final String CHANNEL = "studio";
    static final int MAX_PAYLOAD = 7_500;

    private static final Duration PING = Duration.ofSeconds(10);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(10);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final DataSourceProperties datasource;
    private final ApplicationEventPublisher events;
    private final Set<String> oversized = ConcurrentHashMap.newKeySet();

    private volatile Thread thread;
    private volatile boolean listening;
    private volatile Instant downSince = Instant.now();

    public StudioBus(
            JdbcTemplate jdbc, ObjectMapper mapper, DataSourceProperties datasource, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.datasource = datasource;
        this.events = events;
    }

    /** Whether the listening connection is up: a replica that is not listening misses what others say. */
    public boolean isListening() {
        return listening;
    }

    /** Since when the bus has not been listening; empty while it is. Starts at the process start. */
    public Optional<Instant> downSince() {
        return listening ? Optional.empty() : Optional.of(downSince);
    }

    /**
     * Sends a message to every replica when the current transaction commits, or at once outside one.
     * A frame over the payload cap loses its data; events are split; anything else over it is refused.
     */
    public void publish(BusMessage message) {
        String payload;
        try {
            payload = mapper.writeValueAsString(message);
        } catch (JacksonException e) {
            throw new IllegalArgumentException(
                    "Cannot serialise " + message.getClass().getSimpleName(), e);
        }
        if (payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_PAYLOAD) {
            switch (message) {
                case BusFrame f
                when f.data() != null -> {
                    if (oversized.add(f.topic())) {
                        log.warn(
                                "A '{}' frame is over {} bytes, too large for the bus; it is sent without its data."
                                        + " Further frames of this topic are downgraded silently",
                                f.topic(),
                                MAX_PAYLOAD);
                    }
                    publish(new BusFrame(f.clusterId(), f.topic(), null, f.id()));
                }
                case BusEvents e
                when e.seqs().size() > 1 -> {
                    int half = e.seqs().size() / 2;
                    publish(new BusEvents(e.seqs().subList(0, half)));
                    publish(new BusEvents(e.seqs().subList(half, e.seqs().size())));
                }
                default -> throw new IllegalArgumentException("A bus message is over " + MAX_PAYLOAD + " bytes");
            }
            return;
        }
        jdbc.queryForList("SELECT pg_notify('" + CHANNEL + "', ?)", payload);
    }

    // ---- lifecycle ---------------------------------------------------------------------------------

    @Override
    public void start() {
        if (thread != null) {
            return;
        }
        downSince = Instant.now();
        Thread t = new Thread(this::run, "studio-bus");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    @Override
    public void stop() {
        Thread t = thread;
        thread = null;
        if (t != null) {
            t.interrupt();
            try {
                t.join(Duration.ofSeconds(3));
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }
        listening = false;
    }

    @Override
    public boolean isRunning() {
        return thread != null;
    }

    /** Stops after everything that publishes on it, and before the replica records its stop. */
    @Override
    public int getPhase() {
        return ShutdownPhases.REPLICA + 500;
    }

    private boolean running() {
        return thread == Thread.currentThread() && !Thread.currentThread().isInterrupted();
    }

    private void run() {
        Duration backoff = Duration.ofMillis(500);
        boolean connectedBefore = false;
        while (running()) {
            try (Connection connection = open()) {
                try (Statement listen = connection.createStatement()) {
                    listen.execute("LISTEN " + CHANNEL);
                }
                listening = true;
                backoff = Duration.ofMillis(500);
                if (connectedBefore) {
                    log.info("The studio bus is back");
                    events.publishEvent(new BusResumed());
                }
                connectedBefore = true;
                pump(connection);
            } catch (SQLException | RuntimeException e) {
                if (running()) {
                    log.warn("The studio bus is down, retrying in {}: {}", backoff, e.getMessage());
                }
            }
            if (listening) {
                listening = false;
                downSince = Instant.now();
            }
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException _) {
                return;
            }
            backoff = backoff.multipliedBy(2).compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff.multipliedBy(2);
        }
    }

    private Connection open() throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", datasource.determineUsername());
        properties.setProperty("password", datasource.determinePassword());
        properties.setProperty("tcpKeepAlive", "true");
        properties.setProperty("ApplicationName", "studio-bus");
        return DriverManager.getConnection(datasource.determineUrl(), properties);
    }

    private void pump(Connection connection) throws SQLException {
        PGConnection pg = connection.unwrap(PGConnection.class);
        long nextPing = System.nanoTime() + PING.toNanos();
        while (running()) {
            dispatch(pg.getNotifications(1000));
            if (System.nanoTime() - nextPing >= 0) {
                try (Statement ping = connection.createStatement()) {
                    ping.execute("SELECT 1");
                }
                dispatch(pg.getNotifications());
                nextPing = System.nanoTime() + PING.toNanos();
            }
        }
    }

    private void dispatch(PGNotification[] notifications) {
        if (notifications == null) {
            return;
        }
        for (PGNotification notification : notifications) {
            try {
                events.publishEvent(mapper.readValue(notification.getParameter(), BusMessage.class));
            } catch (JacksonException e) {
                log.warn("Ignoring an unreadable bus message: {}", e.getMessage());
            } catch (RuntimeException e) {
                log.warn("A listener of a bus message failed", e);
            }
        }
    }
}
