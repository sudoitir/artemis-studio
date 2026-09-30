package io.github.sudoitir.artemisstudio.kernel.security.internal;

import io.github.sudoitir.artemisstudio.kernel.core.ConflictException;
import io.github.sudoitir.artemisstudio.kernel.core.NotFoundException;
import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureRegistry;
import io.github.sudoitir.artemisstudio.kernel.security.AdministrationAudit;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RolePermissionEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.UserRoleEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.UserRoleRepository;
import io.github.sudoitir.artemisstudio.kernel.security.web.UserViews.PermissionView;
import io.github.sudoitir.artemisstudio.kernel.security.web.UserViews.RoleRequest;
import io.github.sudoitir.artemisstudio.kernel.security.web.UserViews.RoleView;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Custom role CRUD. Built-in roles ({@code ADMIN}/{@code OPERATOR}/{@code VIEWER},
 * {@code role.builtin = true}) can be granted to users but never renamed, given other permissions
 * or deleted (authorization spec, design.md decision 4) — the permission model is
 * fully dynamic, so this immutability is the only thing stopping an operator
 * from quietly hollowing out a built-in role's meaning. The one thing an administrator may change
 * on a built-in role is whether it requires a second factor (ADR-0142).
 */
@Service
@RequiredArgsConstructor
public class RoleService {

    private final RoleRepository roles;
    private final RolePermissionRepository rolePermissions;
    private final FeatureRegistry features;
    private final UserRoleRepository userRoles;
    private final AdministrationAudit audit;
    private final AppUserRepository users;
    private final SessionTerminator sessions;

    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.kernel.security.Permissions).USER_ADMIN)")
    @Transactional(readOnly = true)
    public List<RoleView> list() {
        return roles.findAllByOrderByName().stream().map(this::toView).toList();
    }

    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.kernel.security.Permissions).USER_ADMIN)")
    public List<PermissionView> catalogue() {
        return features.catalogue().stream()
                .map(e -> new PermissionView(
                        e.action(), e.description(), e.featureId(), e.featureTitle(), e.globalOnly()))
                .toList();
    }

    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.kernel.security.Permissions).USER_ADMIN)")
    @Transactional
    public RoleView create(RoleRequest request) {
        if (roles.findByName(request.name()).isPresent()) {
            throw new ConflictException("duplicate-role-name", "A role named '" + request.name() + "' already exists.");
        }
        RoleEntity role = new RoleEntity(request.name(), false);
        role.setRequiresMfa(request.requiresMfa());
        role = roles.save(role);
        savePermissions(role.getId(), request.permissions());
        audit.changed("ROLE_CREATE", "role", role.getName(), null);
        return toView(role);
    }

    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.kernel.security.Permissions).USER_ADMIN)")
    @Transactional
    public RoleView update(UUID roleId, RoleRequest request) {
        RoleEntity role = roles.findById(roleId).orElseThrow(() -> new NotFoundException("role", roleId));
        boolean mfaChanged = role.isRequiresMfa() != request.requiresMfa();
        if (role.isBuiltin()) {
            requireOnlyMfaChanges(role, request);
        } else {
            role.setName(request.name());
            rolePermissions.deleteByIdRoleId(roleId);
            savePermissions(roleId, request.permissions());
        }
        role.setRequiresMfa(request.requiresMfa());
        roles.save(role);
        audit.changed("ROLE_UPDATE", "role", role.getName(), null);
        // Members' sessions carry the role's old permissions, or were signed in without the factor it
        // now requires; end them so the change applies now. A built-in role's permissions cannot change.
        if (!role.isBuiltin() || mfaChanged) {
            sessions.endSessionsOf(userRoles.findByIdRoleId(roleId).stream()
                    .map(UserRoleEntity::getUserId)
                    .distinct()
                    .flatMap(id -> users.findById(id).stream())
                    .map(AppUserEntity::getUsername)
                    .toList());
        }
        return toView(role);
    }

    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.kernel.security.Permissions).USER_ADMIN)")
    @Transactional
    public void delete(UUID roleId) {
        RoleEntity role = requireEditable(roleId);
        if (userRoles.countByIdRoleId(roleId) > 0) {
            throw new ConflictException("role-in-use", "This role is still granted to at least one user.");
        }
        roles.delete(role); // cascades role_permission
        audit.changed("ROLE_DELETE", "role", role.getName(), null);
    }

    private void savePermissions(UUID roleId, List<String> permissions) {
        for (String action : permissions) {
            rolePermissions.save(new RolePermissionEntity(roleId, action));
        }
    }

    private RoleEntity requireEditable(UUID roleId) {
        RoleEntity role = roles.findById(roleId).orElseThrow(() -> new NotFoundException("role", roleId));
        if (role.isBuiltin()) {
            throw new ConflictException("builtin-role", "Built-in roles cannot be deleted.");
        }
        return role;
    }

    private void requireOnlyMfaChanges(RoleEntity role, RoleRequest request) {
        Set<String> current = rolePermissions.findByIdRoleId(role.getId()).stream()
                .map(RolePermissionEntity::getAction)
                .collect(Collectors.toSet());
        if (!role.getName().equals(request.name()) || !current.equals(new HashSet<>(request.permissions()))) {
            throw new ConflictException(
                    "builtin-role",
                    "Built-in roles keep their name and permissions; only whether they require two-step verification can change.");
        }
    }

    private RoleView toView(RoleEntity role) {
        List<String> permissions = rolePermissions.findByIdRoleId(role.getId()).stream()
                .map(RolePermissionEntity::getAction)
                .toList();
        return new RoleView(role.getId(), role.getName(), role.isBuiltin(), permissions, role.isRequiresMfa());
    }
}
