package io.github.sudoitir.artemisstudio.persist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps {@code broker_event} (changeset 010). Read-only from JPA — rows are
 * written by {@link BrokerEventWriter}'s JDBC batch (ADR-0028), so this entity
 * exists only for the history read API and {@code ddl-auto=validate}.
 */
@Entity
@Table(name = "broker_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class BrokerEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "seq", nullable = false, updatable = false)
    @EqualsAndHashCode.Include
    private Long seq;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "type", nullable = false, updatable = false)
    private String type;

    @Column(name = "address", updatable = false)
    private String address;

    @Column(name = "routing_name", updatable = false)
    private String routingName;

    @Column(name = "consumer_name", updatable = false)
    private String consumerName;

    @Column(name = "session_name", updatable = false)
    private String sessionName;

    @Column(name = "connection_name", updatable = false)
    private String connectionName;

    @Column(name = "remote_address", updatable = false)
    private String remoteAddress;

    @Column(name = "username", updatable = false)
    private String username;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "props", updatable = false)
    private String props;

    @Column(name = "cluster_id", nullable = false, updatable = false)
    private UUID clusterId;

    @Column(name = "node_id", updatable = false)
    private UUID nodeId;
}
