package io.github.sudoitir.artemisstudio.kernel.security.internal;

import io.github.sudoitir.artemisstudio.kernel.gate.ApprovalProvider;
import io.github.sudoitir.artemisstudio.kernel.gate.ApprovalProviderRegistry;
import io.github.sudoitir.artemisstudio.kernel.gate.ApproverPool;
import io.github.sudoitir.artemisstudio.kernel.gate.ApproverQuorumException;
import io.github.sudoitir.artemisstudio.kernel.security.AccessChangeGuard;
import io.github.sudoitir.artemisstudio.kernel.security.Grant;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RolePermissionEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RolePermissionRepository;
import jakarta.persistence.EntityManager;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps an enforcing approval gate from locking itself: an access change that would leave fewer than
 * {@link ApproverPool#QUORUM} approvers, and fewer than there were, is refused before it commits. With one approver
 * every request that is held can only be decided by the person who made it, so nothing could be approved, not even
 * the removal of the policy that holds it.
 *
 * <p>It counts the enabled users who hold a role granting the approver permission for the whole installation, read
 * straight from the tables inside the changing transaction (the access snapshots that decide requests are dropped only
 * after commit, so they cannot see the change). Approval held through a team or a directory group is not counted
 * here, which can only make the guard let a change through that it might have refused, never block one that is safe.
 * A change that does not lower the count is never refused, so a gate already below quorum can still be repaired.
 * Without an armed provider, or while the provider is not enforcing, nothing is checked.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class ApproverQuorumGuard implements AccessChangeGuard {

    private final ApprovalProviderRegistry providers;
    private final RolePermissionRepository rolePermissions;
    private final JdbcClient jdbc;
    private final EntityManager entities;
    private final PlatformTransactionManager transactions;

    @Override
    public void beforeCommit() {
        if (!enforcing()) {
            return;
        }
        String permission = providers.armedApproverPermission().orElse(null);
        if (permission == null) {
            return;
        }
        // The change's own writes must be in the tables this reads.
        entities.flush();
        int after = holders(permission);
        if (after >= ApproverPool.QUORUM) {
            return;
        }
        // What was committed before this change is what another transaction sees.
        Integer before = new TransactionTemplate(transactions, requiresNew()).execute(status -> holders(permission));
        if (before != null && after < before) {
            log.warn(
                    "approval-gate access change refused: approvers {} -> {}, quorum {}",
                    before,
                    after,
                    ApproverPool.QUORUM);
            throw new ApproverQuorumException("Approvals are on and this would leave " + after + " "
                    + (after == 1 ? "person" : "people") + " who can approve, so a request could only be decided by"
                    + " the person who made it and nothing could be approved. Grant the approver permission to"
                    + " another person first, or remove the approval policies.");
        }
    }

    /** Whether the armed provider holds anything now; a provider that cannot say, or is not here, enforces. */
    private boolean enforcing() {
        if (providers.armedProviderId().isEmpty()) {
            return false;
        }
        try {
            return providers.attached().map(ApprovalProvider::enforcing).orElse(true);
        } catch (RuntimeException e) {
            log.warn("approval-gate provider could not say whether it enforces; treating it as enforcing", e);
            return true;
        }
    }

    private static TransactionDefinition requiresNew() {
        org.springframework.transaction.support.DefaultTransactionDefinition definition =
                new org.springframework.transaction.support.DefaultTransactionDefinition();
        definition.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        definition.setReadOnly(true);
        return definition;
    }

    /** Enabled users with a global role grant whose role's permissions cover {@code permission}. */
    private int holders(String permission) {
        Map<UUID, Set<String>> byRole = new HashMap<>();
        for (RolePermissionEntity row : rolePermissions.findAll()) {
            byRole.computeIfAbsent(row.getRoleId(), id -> new HashSet<>()).add(row.getAction());
        }
        List<UUID> covering = byRole.entrySet().stream()
                .filter(entry -> Grant.covers(entry.getValue(), permission))
                .map(Map.Entry::getKey)
                .toList();
        if (covering.isEmpty()) {
            return 0;
        }
        return jdbc.sql("""
                        SELECT count(DISTINCT u.id) FROM app_user u
                        JOIN user_role r ON r.user_id = u.id
                        WHERE NOT u.disabled AND r.scope_type = 'GLOBAL' AND r.role_id = ANY (?)""")
                .param(covering.toArray(UUID[]::new))
                .query(Integer.class)
                .single();
    }
}
