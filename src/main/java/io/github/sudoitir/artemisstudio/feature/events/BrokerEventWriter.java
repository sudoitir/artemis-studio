package io.github.sudoitir.artemisstudio.feature.events;

import io.github.sudoitir.artemisstudio.feature.events.internal.persistence.BrokerEventEntity;
import io.github.sudoitir.artemisstudio.feature.events.internal.persistence.BrokerEventRepository;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerEvent;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerEventSink;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Buffered batch writer for {@code broker_event} (ADR-0028). Notifications arrive
 * on the Core client's drain thread; a synchronous insert per notification would
 * couple broker chatter to database latency. Instead {@link #accept} enqueues
 * without blocking, a scheduled {@link #flush} does one JDBC batch, and the
 * buffer is bounded: overflow is dropped and counted per cluster, surfaced by
 * the events API rather than silently.
 */
@Component
@Slf4j
public class BrokerEventWriter implements BrokerEventSink {

    private static final int BATCH_MAX = 500;

    private static final String INSERT = """
            INSERT INTO broker_event
              (occurred_at, type, address, routing_name, consumer_name, session_name,
               connection_name, remote_address, username, props, cluster_id, node_id)
            VALUES
              (:occurredAt, :type, :address, :routingName, :consumerName, :sessionName,
               :connectionName, :remoteAddress, :username, CAST(:props AS jsonb), :clusterId, :nodeId)
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final BrokerEventRepository repository;
    private final ObjectProvider<BrokerEventPublisher> publisher;

    /** This bean through its proxy, so the shutdown drain's flushes are transactional. */
    private final ObjectProvider<BrokerEventWriter> self;

    private final LinkedBlockingDeque<BrokerEvent> buffer = new LinkedBlockingDeque<>();
    private final Map<UUID, AtomicLong> dropped = new ConcurrentHashMap<>();
    private volatile int capacity;

    public BrokerEventWriter(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper mapper,
            BrokerEventRepository repository,
            ObjectProvider<BrokerEventPublisher> publisher,
            ObjectProvider<BrokerEventWriter> self,
            EventsProperties properties) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.repository = repository;
        this.publisher = publisher;
        this.self = self;
        this.capacity = Math.max(1, properties.bufferSize());
    }

    /** Runtime override hook — {@code SettingsService} calls this when the setting changes. */
    public void setCapacity(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    public int capacity() {
        return capacity;
    }

    @Override
    public void accept(BrokerEvent event) {
        if (buffer.size() >= capacity) {
            dropped.computeIfAbsent(event.clusterId(), k -> new AtomicLong()).incrementAndGet();
            return;
        }
        buffer.offer(event);
    }

    public long droppedFor(UUID clusterId) {
        AtomicLong count = dropped.get(clusterId);
        return count == null ? 0 : count.get();
    }

    /**
     * Scheduled by {@code JobScheduler} on the settings-driven flush interval, and run once
     * more at shutdown. A batch whose insert fails is put back at the head of the buffer,
     * in order, and the failure is rethrown so the job reports it: a database outage delays
     * events rather than discarding the batch that happened to be in flight.
     */
    @Transactional
    public void flush() {
        List<BrokerEvent> batch = new ArrayList<>(BATCH_MAX);
        buffer.drainTo(batch, BATCH_MAX);
        if (batch.isEmpty()) {
            return;
        }
        try {
            write(batch);
        } catch (RuntimeException e) {
            requeue(batch);
            throw e;
        }
    }

    /**
     * Flush until the buffer is empty or a write fails — the shutdown flush. A failure is
     * logged rather than thrown: shutdown continues, and what could not be written is what
     * the buffer still holds.
     */
    public void drain() {
        try {
            while (!buffer.isEmpty()) {
                self.getObject().flush();
            }
        } catch (RuntimeException e) {
            log.warn("{} broker event(s) could not be written before shutdown: {}", buffer.size(), e.getMessage());
        }
    }

    /** Back at the head in their original order. What no longer fits is counted as dropped, as on intake. */
    private void requeue(List<BrokerEvent> batch) {
        for (int i = batch.size() - 1; i >= 0; i--) {
            BrokerEvent event = batch.get(i);
            if (buffer.size() >= capacity) {
                dropped.computeIfAbsent(event.clusterId(), k -> new AtomicLong())
                        .incrementAndGet();
                continue;
            }
            buffer.offerFirst(event);
        }
    }

    private void write(List<BrokerEvent> batch) {
        long previousMaxSeq = repository
                .findFirstByOrderBySeqDesc()
                .map(BrokerEventEntity::getSeq)
                .orElse(0L);

        SqlParameterSource[] params = batch.stream().map(this::params).toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate(INSERT, params);

        BrokerEventPublisher sink = publisher.getIfAvailable();
        if (sink != null) {
            sink.published(repository.findBySeqGreaterThanOrderBySeqAsc(previousMaxSeq));
        }
    }

    private SqlParameterSource params(BrokerEvent e) {
        return new MapSqlParameterSource()
                .addValue("occurredAt", Timestamp.from(e.occurredAt()))
                .addValue("type", e.type())
                .addValue("address", e.address())
                .addValue("routingName", e.routingName())
                .addValue("consumerName", e.consumerName())
                .addValue("sessionName", e.sessionName())
                .addValue("connectionName", e.connectionName())
                .addValue("remoteAddress", e.remoteAddress())
                .addValue("username", e.username())
                .addValue("props", toJson(e.props()))
                .addValue("clusterId", e.clusterId())
                .addValue("nodeId", e.nodeId());
    }

    private String toJson(Map<String, Object> props) {
        if (props == null || props.isEmpty()) {
            return null;
        }
        try {
            return mapper.writeValueAsString(props);
        } catch (JacksonException e) {
            log.debug("Could not serialise notification props: {}", e.getMessage());
            return null;
        }
    }
}
