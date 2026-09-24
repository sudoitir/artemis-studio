package io.github.sudoitir.artemisstudio.feature.alerting.internal.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@code alert_delivery} access. {@link #claimDue} is the dispatcher's only read —
 * {@code FOR UPDATE SKIP LOCKED} inside its caller's transaction so a concurrent
 * instance never double-sends the same row (design.md decision 5, ADR-0015's
 * multi-instance seam). The rest serve the channel screen's health and delivery log
 * (ADR-0105 D6).
 */
public interface AlertDeliveryRepository extends JpaRepository<AlertDeliveryEntity, Long> {

    @Query(
            value = "SELECT * FROM alert_delivery WHERE state = 'PENDING' AND next_attempt_at <= now() "
                    + "ORDER BY seq LIMIT :limit FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<AlertDeliveryEntity> claimDue(@Param("limit") int limit);

    /** A channel's delivery log, newest first. Served by {@code ix_alert_delivery_channel_seq}. */
    List<AlertDeliveryEntity> findByChannelIdOrderBySeqDesc(UUID channelId, Pageable page);

    /** Each channel's newest delivery, one index probe per channel rather than a scan of the ledger. */
    @Query(
            value = "SELECT d.* FROM notification_channel c CROSS JOIN LATERAL ("
                    + "SELECT * FROM alert_delivery d WHERE d.channel_id = c.id ORDER BY d.seq DESC LIMIT 1) d",
            nativeQuery = true)
    List<AlertDeliveryEntity> latestPerChannel();

    /** Waiting deliveries, and what succeeded and died since {@code since}, per channel. */
    @Query(
            value = "SELECT channel_id AS channelId,"
                    + " count(*) FILTER (WHERE state = 'PENDING') AS pending,"
                    + " count(*) FILTER (WHERE state = 'DEAD' AND created_at >= :since) AS failed,"
                    + " count(*) FILTER (WHERE state = 'SENT' AND created_at >= :since) AS sent"
                    + " FROM alert_delivery WHERE state = 'PENDING' OR created_at >= :since"
                    + " GROUP BY channel_id",
            nativeQuery = true)
    List<ChannelDeliveryCounts> countsSince(@Param("since") Instant since);

    interface ChannelDeliveryCounts {
        UUID getChannelId();

        long getPending();

        long getFailed();

        long getSent();
    }
}
