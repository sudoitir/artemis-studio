package io.github.sudoitir.artemisstudio.persist;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@code alert_delivery} access. {@link #claimDue} is the dispatcher's only read —
 * {@code FOR UPDATE SKIP LOCKED} inside its caller's transaction so a concurrent
 * instance never double-sends the same row (design.md decision 5, ADR-0015's
 * multi-instance seam).
 */
public interface AlertDeliveryRepository extends JpaRepository<AlertDeliveryEntity, Long> {

    @Query(
            value = "SELECT * FROM alert_delivery WHERE state = 'PENDING' AND next_attempt_at <= now() "
                    + "ORDER BY seq LIMIT :limit FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<AlertDeliveryEntity> claimDue(@Param("limit") int limit);
}
