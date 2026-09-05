package io.github.sudoitir.artemisstudio.service;

import io.github.sudoitir.artemisstudio.persist.AuditService;
import io.github.sudoitir.artemisstudio.persist.RoleEntity;
import io.github.sudoitir.artemisstudio.persist.RolePermissionEntity;
import io.github.sudoitir.artemisstudio.persist.RolePermissionRepository;
import io.github.sudoitir.artemisstudio.persist.RoleRepository;
import io.github.sudoitir.artemisstudio.persist.UserRoleRepository;
import io.github.sudoitir.artemisstudio.security.ActorResolver;
import io.github.sudoitir.artemisstudio.security.Permissions;
import io.github.sudoitir.artemisstudio.web.dto.UserViews.PermissionView;
import io.github.sudoitir.artemisstudio.web.dto.UserViews.RoleRequest;
import io.github.sudoitir.artemisstudio.web.dto.UserViews.RoleView;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Custom role CRUD. Built-in roles ({@code ADMIN}/{@code OPERATOR}/{@code VIEWER},
 * {@code role.builtin = true}) can be granted to users but never edited or
 * deleted (authorization spec, design.md decision 4) — the permission model is
 * fully dynamic, so this immutability is the only thing stopping an operator
 * from quietly hollowing out a built-in role's meaning.
 */
@Service
@RequiredArgsConstructor
public class RoleService {

    private final RoleRepository roles;
    private final RolePermissionRepository rolePermissions;
    private final UserRoleRepository userRoles;
    private final AuditService audit;
    private final ActorResolver actorResolver;

    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.security.Permissions).USER_ADMIN)")
    @Transactional(readOnly = true)
    public List<RoleView> list() {
        return roles.findAllByOrderByName().stream().map(this::toView).toList();
    }

    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.security.Permissions).USER_ADMIN)")
    public List<PermissionView> catalogue() {
        return Permissions.catalogue().entrySet().stream()
                .map(e -> new PermissionView(e.getKey(), e.getValue()))
                .toList();
    }

    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.security.Permissions).USER_ADMIN)")
    @Transactional
    public RoleView create(RoleRequest request) {
        if (roles.findByName(request.name()).isPresent()) {
            throw new ConflictException("duplicate-role-name", "A role named '" + request.name() + "' already exists.");
        }
        RoleEntity role = roles.save(new RoleEntity(request.name(), false));
        savePermissions(role.getId(), request.permissions());
        audit.succeed(
                audit.begin(actorResolver.resolve(), "ROLE_CREATE", "role", role.getName(), null, null, null, false),
                1);
        return toView(role);
    }

    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.security.Permissions).USER_ADMIN)")
    @Transactional
    public RoleView update(UUID roleId, RoleRequest request) {
        RoleEntity role = requireEditable(roleId);
        role.setName(request.name());
        roles.save(role);
        rolePermissions.deleteByIdRoleId(roleId);
        savePermissions(roleId, request.permissions());
        audit.succeed(
                audit.begin(actorResolver.resolve(), "ROLE_UPDATE", "role", role.getName(), null, null, null, false),
                1);
        return toView(role);
    }

    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.security.Permissions).USER_ADMIN)")
    @Transactional
    public void delete(UUID roleId) {
        RoleEntity role = requireEditable(roleId);
        if (userRoles.countByIdRoleId(roleId) > 0) {
            throw new ConflictException("role-in-use", "This role is still granted to at least one user.");
        }
        roles.delete(role); // cascades role_permission
        audit.succeed(
                audit.begin(actorResolver.resolve(), "ROLE_DELETE", "role", role.getName(), null, null, null, false),
                1);
    }

    private void savePermissions(UUID roleId, List<String> permissions) {
        for (String action : permissions) {
            rolePermissions.save(new RolePermissionEntity(roleId, action));
        }
    }

    private RoleEntity requireEditable(UUID roleId) {
        RoleEntity role = roles.findById(roleId).orElseThrow(() -> new NotFoundException("role", roleId));
        if (role.isBuiltin()) {
            throw new ConflictException("builtin-role", "Built-in roles cannot be changed or deleted.");
        }
        return role;
    }

    private RoleView toView(RoleEntity role) {
        List<String> permissions = rolePermissions.findByIdRoleId(role.getId()).stream()
                .map(RolePermissionEntity::getAction)
                .toList();
        return new RoleView(role.getId(), role.getName(), role.isBuiltin(), permissions);
    }
}
