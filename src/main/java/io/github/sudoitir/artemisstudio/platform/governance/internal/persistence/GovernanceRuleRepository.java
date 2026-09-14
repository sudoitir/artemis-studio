package io.github.sudoitir.artemisstudio.platform.governance.internal.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface GovernanceRuleRepository extends JpaRepository<GovernanceRuleEntity, UUID> {

    List<GovernanceRuleEntity> findAllByOrderByBuiltinDescCreatedAtAsc();

    @Query(value = "SELECT version FROM governance_policy WHERE id = 1", nativeQuery = true)
    int policyVersion();

    /** Every rule write bumps the version in the same transaction, so stored rows know they are stale. */
    @Modifying
    @Query(
            value = "UPDATE governance_policy SET version = version + 1, updated_at = now() WHERE id = 1",
            nativeQuery = true)
    void bumpPolicyVersion();
}
