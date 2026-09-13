package io.github.sudoitir.artemisstudio.persist;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BrokerConfigApplyRepository extends JpaRepository<BrokerConfigApplyEntity, Long> {

    List<BrokerConfigApplyEntity> findByClusterIdOrderByStartedAtDesc(UUID clusterId, Pageable pageable);
}
