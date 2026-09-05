package io.github.sudoitir.artemisstudio.service;

import io.github.sudoitir.artemisstudio.persist.AppUserEntity;
import io.github.sudoitir.artemisstudio.persist.AppUserRepository;
import io.github.sudoitir.artemisstudio.persist.AuditService;
import io.github.sudoitir.artemisstudio.persist.RoleEntity;
import io.github.sudoitir.artemisstudio.persist.RoleRepository;
import io.github.sudoitir.artemisstudio.persist.UserRoleEntity;
import io.github.sudoitir.artemisstudio.persist.UserRoleRepository;
import io.github.sudoitir.artemisstudio.security.ActorResolver;
import io.github.sudoitir.artemisstudio.security.ScopeIds;
import io.github.sudoitir.artemisstudio.security.StudioPrincipal;
import io.github.sudoitir.artemisstudio.web.dto.UserViews.CreateUserRequest;
import io.github.sudoitir.artemisstudio.web.dto.UserViews.GrantRequest;
import io.github.sudoitir.artemisstudio.web.dto.UserViews.GrantSummary;
import io.github.sudoitir.artemisstudio.web.dto.UserViews.UserView;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * User administration: create/disable local accounts and grant/revoke role
 * assignments (authorization spec). The last-global-administrator guards are
 * the load-bearing safety net for the fully-dynamic permission model
 * (design.md decision 4) — enforced here, in the same transaction as the
 * mutation, never left to the UI alone (ADR-0022 precedent).
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final AppUserRepository users;
    private final RoleRepository roles;
    private final UserRoleRepository userRoles;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;
    private final ActorResolver actorResolver;

    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.security.Permissions).USER_ADMIN)")
    @Transactional(readOnly = true)
    public List<UserView> list() {
        return users.findAllByOrderByUsername().stream().map(this::toView).toList();
    }

    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.security.Permissions).USER_ADMIN)")
    @Transactional
    public UserView create(CreateUserRequest request) {
        if (users.findByUsername(request.username()).isPresent()) {
            throw new ConflictException(
                    "duplicate-username", "A user named '" + request.username() + "' already exists.");
        }
        AppUserEntity user =
                AppUserEntity.local(request.username(), request.email(), passwordEncoder.encode(request.password()));
        user.setMustChangePassword(true);
        users.save(user);
        audit.succeed(
                audit.begin(
                        actorResolver.resolve(), "USER_CREATE", "user", user.getUsername(), null, null, null, false),
                1);
        return toView(user);
    }

    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.security.Permissions).USER_ADMIN)")
    @Transactional
    public UserView setDisabled(UUID userId, boolean disabled) {
        AppUserEntity user = requireUser(userId);
        if (disabled) {
            guardNotLastAdmin(user, "disable");
        }
        user.setDisabled(disabled);
        users.save(user);
        audit.succeed(
                audit.begin(
                        actorResolver.resolve(),
                        disabled ? "USER_DISABLE" : "USER_ENABLE",
                        "user",
                        user.getUsername(),
                        null,
                        null,
                        null,
                        false),
                1);
        return toView(user);
    }

    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.security.Permissions).USER_ADMIN)")
    @Transactional
    public void addGrant(UUID userId, GrantRequest request) {
        AppUserEntity user = requireUser(userId);
        RoleEntity role =
                roles.findById(request.roleId()).orElseThrow(() -> new NotFoundException("role", request.roleId()));
        UUID scopeId = request.scopeId() != null ? request.scopeId() : ScopeIds.GLOBAL;
        userRoles.save(new UserRoleEntity(userId, role.getId(), request.scopeType(), scopeId));
        audit.succeed(
                audit.begin(
                        actorResolver.resolve(),
                        "GRANT_ADD",
                        "user",
                        user.getUsername(),
                        null,
                        null,
                        Map.of("role", role.getName(), "scopeType", request.scopeType()),
                        false),
                1);
    }

    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.security.Permissions).USER_ADMIN)")
    @Transactional
    public void removeGrant(UUID userId, UUID roleId, String scopeType, UUID scopeId) {
        AppUserEntity user = requireUser(userId);
        RoleEntity role = roles.findById(roleId).orElseThrow(() -> new NotFoundException("role", roleId));
        UUID resolvedScopeId = scopeId != null ? scopeId : ScopeIds.GLOBAL;

        boolean isGlobalAdminGrant =
                "GLOBAL".equals(scopeType) && role.getName().equals("ADMIN") && ScopeIds.GLOBAL.equals(resolvedScopeId);
        if (isGlobalAdminGrant) {
            guardNotLastAdmin(user, "strip the administrator role from");
            StudioPrincipal principal = currentPrincipalOrNull();
            if (principal != null && principal.userId().equals(userId)) {
                throw new ConflictException("self-revoke-admin", "You cannot remove your own administrator grant.");
            }
        }

        userRoles.deleteById(new UserRoleEntity(userId, roleId, scopeType, resolvedScopeId).getId());
        audit.succeed(
                audit.begin(
                        actorResolver.resolve(),
                        "GRANT_REMOVE",
                        "user",
                        user.getUsername(),
                        null,
                        null,
                        Map.of("role", role.getName(), "scopeType", scopeType),
                        false),
                1);
    }

    private void guardNotLastAdmin(AppUserEntity user, String verb) {
        RoleEntity admin = roles.findByName("ADMIN").orElseThrow(() -> new IllegalStateException("ADMIN role missing"));
        long adminHolders = userRoles.findByIdRoleId(admin.getId()).stream()
                .filter(ur -> "GLOBAL".equals(ur.getScopeType()))
                .map(UserRoleEntity::getUserId)
                .distinct()
                .filter(id -> users.findById(id).map(u -> !u.isDisabled()).orElse(false))
                .count();
        boolean userHoldsAdmin = userRoles.findByIdUserId(user.getId()).stream()
                .anyMatch(ur -> ur.getRoleId().equals(admin.getId()) && "GLOBAL".equals(ur.getScopeType()));
        if (userHoldsAdmin && adminHolders <= 1) {
            throw new ConflictException("last-admin", "Cannot " + verb + " the last enabled global administrator.");
        }
    }

    private static StudioPrincipal currentPrincipalOrNull() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext()
                .getAuthentication();
        return auth != null && auth.getPrincipal() instanceof StudioPrincipal p ? p : null;
    }

    private AppUserEntity requireUser(UUID userId) {
        return users.findById(userId).orElseThrow(() -> new NotFoundException("user", userId));
    }

    private UserView toView(AppUserEntity user) {
        List<GrantSummary> grants = userRoles.findByIdUserId(user.getId()).stream()
                .map(ur -> new GrantSummary(
                        roles.findById(ur.getRoleId()).map(RoleEntity::getName).orElse("?"),
                        ur.getRoleId(),
                        ur.getScopeType(),
                        ScopeIds.GLOBAL.equals(ur.getScopeId()) ? null : ur.getScopeId()))
                .toList();
        return new UserView(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getAuthSource(),
                user.isDisabled(),
                user.isMustChangePassword(),
                grants);
    }
}
