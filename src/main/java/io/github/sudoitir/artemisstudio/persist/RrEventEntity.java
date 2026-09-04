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
 * Maps {@code rr_event} (changesets 007, 011): the raw observed lifecycle
 * events behind one {@code rr_flow} — append-only, insertion order is the
 * identity, same PK shape as {@code broker_event} (ADR-0028's exception to the
 * uuid-PK convention for a log).
 */
@Entity
@Table(name = "rr_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class RrEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "seq", nullable = false, updatable = false)
    @EqualsAndHashCode.Include
    private Long seq;

    @Column(name = "flow_id", nullable = false, updatable = false)
    private UUID flowId;

    @Column(name = "node_id", updatable = false)
    private UUID nodeId;

    @Column(name = "kind", nullable = false, updatable = false)
    private String kind;

    @Column(name = "ts", nullable = false, updatable = false)
    private Instant ts;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail", updatable = false)
    private String detail;

    public RrEventEntity(UUID flowId, UUID nodeId, String kind, Instant ts, String detail) {
        this.flowId = flowId;
        this.nodeId = nodeId;
        this.kind = kind;
        this.ts = ts;
        this.detail = detail;
    }
}
