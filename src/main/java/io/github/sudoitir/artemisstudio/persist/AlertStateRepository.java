package io.github.sudoitir.artemisstudio.persist;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertStateRepository extends JpaRepository<AlertStateEntity, AlertStateEntity.Key> {

    List<AlertStateEntity> findByRuleId(UUID ruleId);
}
