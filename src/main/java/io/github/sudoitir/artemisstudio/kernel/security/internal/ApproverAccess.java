package io.github.sudoitir.artemisstudio.kernel.security.internal;

import io.github.sudoitir.artemisstudio.kernel.gate.ApprovalProviderRegistry;
import io.github.sudoitir.artemisstudio.kernel.security.Grant;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RolePermissionEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.UserRoleEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.UserRoleRepository;
import java.util.Collection;
import java.util.HashSet;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Whether an access change touches the permission the armed approval provider's approvers hold. Such a change
 * could leave the gate without approvers, so it carries {@code GATE_INTEGRITY} (ADR-0179, threat 22). Without an
 * armed provider nothing is touched.
 */
@Component
@RequiredArgsConstructor
class ApproverAccess {

    private final ApprovalProviderRegistry providers;
    private final RolePermissionRepository rolePermissions;
    private final UserRoleRepository userRoles;

    /** Whether holding exactly these permissions makes someone an approver. */
    boolean grantsApproval(Collection<String> permissions) {
        return providers
                .armedApproverPermission()
                .map(approver -> Grant.covers(new HashSet<>(permissions), approver))
                .orElse(false);
    }

    /**
     * Whether a provider is armed. Approvers may hold the permission through a team, a share or a directory group,
     * which these checks do not follow, so a change that could take one away is tagged whenever the gate is armed.
     */
    boolean armed() {
        return providers.armedProviderId().isPresent();
    }

    boolean roleGrantsApproval(UUID roleId) {
        return roleId != null
                && grantsApproval(rolePermissions.findByIdRoleId(roleId).stream()
                        .map(RolePermissionEntity::getAction)
                        .toList());
    }

    boolean userHoldsApproval(UUID userId) {
        return userRoles.findByIdUserId(userId).stream()
                .map(UserRoleEntity::getRoleId)
                .anyMatch(this::roleGrantsApproval);
    }
}
