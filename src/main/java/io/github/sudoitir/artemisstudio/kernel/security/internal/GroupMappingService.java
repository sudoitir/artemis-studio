package io.github.sudoitir.artemisstudio.kernel.security.internal;

import io.github.sudoitir.artemisstudio.kernel.core.NotFoundException;
import io.github.sudoitir.artemisstudio.kernel.security.ScopeIds;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.DefaultRoleEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.DefaultRoleRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.GroupMappingEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.GroupMappingRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.artemisstudio.kernel.security.web.GroupMappingViews.GroupMappingRequest;
import io.github.sudoitir.artemisstudio.kernel.security.web.GroupMappingViews.GroupMappingView;
import io.github.sudoitir.artemisstudio.kernel.security.web.GroupMappingViews.GroupMappingsView;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Group mappings and default roles for external identity providers (ADR-0073). Every call needs
 * {@code user:admin}. The local provider has no groups, and an unknown provider is not found.
 */
@Service
@RequiredArgsConstructor
public class GroupMappingService {

    private final GroupMappingRepository mappings;
    private final DefaultRoleRepository defaultRoles;
    private final RoleRepository roles;
    private final IdentityProviderCatalog providers;

    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.kernel.security.Permissions).USER_ADMIN)")
    @Transactional(readOnly = true)
    public GroupMappingsView list(String providerId) {
        requireExternal(providerId);
        return view(providerId);
    }

    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.kernel.security.Permissions).USER_ADMIN)")
    @Transactional
    public GroupMappingView create(String providerId, GroupMappingRequest request) {
        requireExternal(providerId);
        requireRole(request.roleId());
        UUID scopeId = request.scopeId() != null ? request.scopeId() : ScopeIds.GLOBAL;
        return toView(mappings.save(new GroupMappingEntity(
                providerId, request.groupName(), request.roleId(), request.scopeType(), scopeId)));
    }

    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.kernel.security.Permissions).USER_ADMIN)")
    @Transactional
    public void delete(String providerId, UUID mappingId) {
        requireExternal(providerId);
        mappings.delete(mappings.findByIdAndProviderId(mappingId, providerId)
                .orElseThrow(() -> new NotFoundException("group mapping", mappingId)));
    }

    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.kernel.security.Permissions).USER_ADMIN)")
    @Transactional
    public GroupMappingsView setDefaultRole(String providerId, UUID roleId) {
        requireExternal(providerId);
        if (roleId == null) {
            defaultRoles.deleteById(providerId);
        } else {
            requireRole(roleId);
            defaultRoles.save(new DefaultRoleEntity(providerId, roleId));
        }
        return view(providerId);
    }

    private GroupMappingsView view(String providerId) {
        return new GroupMappingsView(
                defaultRoles
                        .findById(providerId)
                        .map(DefaultRoleEntity::getRoleId)
                        .orElse(null),
                mappings.findByProviderIdOrderByGroupNameAsc(providerId).stream()
                        .map(this::toView)
                        .toList());
    }

    private void requireExternal(String providerId) {
        boolean known = !LoginService.DEFAULT_PROVIDER.equals(providerId)
                && providers.providers().stream().anyMatch(p -> p.id().equals(providerId));
        if (!known) {
            throw new NotFoundException("identity provider", providerId);
        }
    }

    private void requireRole(UUID roleId) {
        roles.findById(roleId).orElseThrow(() -> new NotFoundException("role", roleId));
    }

    private GroupMappingView toView(GroupMappingEntity m) {
        String roleName = roles.findById(m.getRoleId()).map(RoleEntity::getName).orElse("?");
        return new GroupMappingView(
                m.getId(), m.getGroupName(), m.getRoleId(), roleName, m.getScopeType(), m.getScopeId());
    }
}
