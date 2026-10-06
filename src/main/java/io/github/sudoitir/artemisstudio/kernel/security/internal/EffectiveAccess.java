package io.github.sudoitir.artemisstudio.kernel.security.internal;

import io.github.sudoitir.artemisstudio.kernel.core.NotFoundException;
import io.github.sudoitir.artemisstudio.kernel.plugin.CatalogueEntry;
import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureRegistry;
import io.github.sudoitir.artemisstudio.kernel.plugin.PermissionScope;
import io.github.sudoitir.artemisstudio.kernel.plugin.ResourceKind;
import io.github.sudoitir.artemisstudio.kernel.security.Grant;
import io.github.sudoitir.artemisstudio.kernel.security.ResourceRef;
import io.github.sudoitir.artemisstudio.kernel.security.ScopeHierarchy;
import io.github.sudoitir.artemisstudio.kernel.security.TeamIndex;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RolePermissionEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.TeamEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.TeamMemberEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.TeamMemberRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.TeamRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.TeamShareRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.UserRoleEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.UserRoleRepository;
import io.github.sudoitir.artemisstudio.kernel.security.web.AccessViews.AccessCheckView;
import io.github.sudoitir.artemisstudio.kernel.security.web.AccessViews.AccessSource;
import io.github.sudoitir.artemisstudio.kernel.security.web.AccessViews.SourceType;
import io.github.sudoitir.artemisstudio.kernel.security.web.UserViews.EffectivePermissionView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A user's effective permissions, per grant scope and source role, with wildcards expanded against
 * the catalogue (authorization spec). Mirrors {@code GrantLoader}, which merges roles per scope and
 * so cannot say where a permission came from.
 */
@Service
@RequiredArgsConstructor
public class EffectiveAccess {

    static final String GLOBAL_ONLY = "Acts only at global scope";
    static final String NOT_CATALOGUED = "Not in the catalogue: its module or plugin is not active";

    private final UserRoleRepository userRoles;
    private final RoleRepository roles;
    private final RolePermissionRepository rolePermissions;
    private final AppUserRepository users;
    private final FeatureRegistry features;
    private final ScopeHierarchy environments;
    private final TeamIndex teamIndex;
    private final TeamMemberRepository members;
    private final TeamRepository teams;
    private final TeamShareRepository shares;

    /** Guarded before the body runs, so a refused caller learns nothing, not even whether the user exists. */
    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.kernel.security.Permissions).USER_ADMIN)")
    @Transactional(readOnly = true)
    public List<EffectivePermissionView> of(UUID userId) {
        if (!users.existsById(userId)) {
            throw new NotFoundException("user", userId);
        }
        List<CatalogueEntry> catalogue = features.catalogue();
        List<EffectivePermissionView> result = new ArrayList<>();
        for (UserRoleEntity row : userRoles.findByIdUserId(userId)) {
            RoleEntity role = roles.findById(row.getRoleId()).orElse(null);
            if (role == null) {
                continue;
            }
            Grant.ScopeType scope = Grant.ScopeType.valueOf(row.getScopeType());
            for (RolePermissionEntity held : rolePermissions.findByIdRoleId(role.getId())) {
                String pattern = held.getAction();
                boolean wildcard = pattern.equals("*") || pattern.endsWith(":*");
                if (wildcard) {
                    Grant grant = new Grant(scope, row.getScopeId(), Set.of(pattern));
                    catalogue.stream()
                            .filter(e -> grant.grants(e.action()))
                            .forEach(e -> result.add(view(e.action(), e, scope, row, role, pattern)));
                } else {
                    CatalogueEntry entry = catalogue.stream()
                            .filter(e -> e.action().equals(pattern))
                            .findFirst()
                            .orElse(null);
                    result.add(view(pattern, entry, scope, row, role, pattern));
                }
            }
        }
        result.sort(Comparator.comparing(EffectivePermissionView::scopeType)
                .thenComparing(v -> String.valueOf(v.scopeId()))
                .thenComparing(EffectivePermissionView::action)
                .thenComparing(EffectivePermissionView::roleName));
        return result;
    }

    /**
     * For every catalogue permission, whether the user holds it here and each way they do: the role grants that
     * reach the cluster (global, its environment, or the cluster), the team role on the team that owns the named
     * queue or address, and the shares that cover it. Mirrors what {@code PermissionResolver} decides for a
     * request, so the answer is the one the user would meet, with the reasons the resolver does not keep.
     *
     * @param clusterId the cluster, or null to ask only about global grants
     * @param kind the kind of {@code name}; both are given, or neither
     * @param name a queue or address of the cluster, or null for the cluster as a whole
     */
    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.kernel.security.Permissions).USER_ADMIN)")
    @Transactional(readOnly = true)
    public List<AccessCheckView> check(UUID userId, UUID clusterId, ResourceKind kind, String name) {
        AppUserEntity user = users.findById(userId).orElseThrow(() -> new NotFoundException("user", userId));
        if ((kind == null) != (name == null || name.isBlank())) {
            throw new IllegalArgumentException("Give both the kind and the name of the queue or address, or neither.");
        }
        if (name != null && clusterId == null) {
            throw new IllegalArgumentException("A queue or address belongs to a cluster: choose the cluster too.");
        }
        ResourceRef resource = name == null ? null : new ResourceRef(kind, name.strip());
        UUID environmentId = clusterId == null ? null : environments.environmentOf(clusterId);
        Map<UUID, RoleEntity> roleById =
                roles.findAll().stream().collect(Collectors.toMap(RoleEntity::getId, Function.identity()));
        Map<UUID, Set<String>> rolePerms = new HashMap<>();
        roleById.keySet().forEach(id -> rolePerms.put(id, permissionsOf(id)));
        List<UserRoleEntity> grants = user.isDisabled() ? List.of() : userRoles.findByIdUserId(userId);
        List<TeamMemberEntity> held = user.isDisabled() ? List.of() : members.findHeldBy(userId);
        Map<UUID, String> teamNames =
                teams.findAll().stream().collect(Collectors.toMap(TeamEntity::getId, TeamEntity::getName));
        Optional<UUID> owner = resource == null ? Optional.empty() : teamIndex.ownerOf(clusterId, resource);
        List<TeamIndex.Shared> covering = resource == null ? List.of() : teamIndex.sharesCovering(clusterId, resource);

        List<AccessCheckView> result = new ArrayList<>();
        for (CatalogueEntry entry : features.catalogue()) {
            List<AccessSource> sources = new ArrayList<>();
            for (UserRoleEntity row : grants) {
                RoleEntity role = roleById.get(row.getRoleId());
                Grant.ScopeType scope = Grant.ScopeType.valueOf(row.getScopeType());
                if (role != null
                        && reaches(scope, row.getScopeId(), entry, clusterId, environmentId)
                        && Grant.covers(rolePerms.get(role.getId()), entry.action())) {
                    UUID scopeId = scope == Grant.ScopeType.GLOBAL ? null : row.getScopeId();
                    sources.add(new AccessSource(
                            SourceType.ROLE_GRANT, role.getName(), scope.name(), scopeId, null, null, null));
                }
            }
            if (entry.scope() == PermissionScope.RESOURCE
                    && resource != null
                    && entry.resourceKinds().contains(resource.kind())) {
                for (TeamMemberEntity member : held) {
                    RoleEntity role = roleById.get(member.getRoleId());
                    if (role != null
                            && owner.filter(member.getTeamId()::equals).isPresent()
                            && Grant.covers(rolePerms.get(role.getId()), entry.action())) {
                        sources.add(new AccessSource(
                                SourceType.TEAM,
                                role.getName(),
                                null,
                                null,
                                member.getTeamId(),
                                teamNames.get(member.getTeamId()),
                                null));
                    }
                }
                for (TeamIndex.Shared share : covering) {
                    if (held.stream().anyMatch(m -> m.getTeamId().equals(share.targetTeamId()))
                            && Grant.covers(share.permissions(), entry.action())) {
                        sources.add(new AccessSource(
                                SourceType.SHARE,
                                shareRoleName(share, roleById),
                                null,
                                null,
                                share.targetTeamId(),
                                teamNames.get(share.targetTeamId()),
                                teamNames.get(share.ownerTeamId())));
                    }
                }
            }
            result.add(new AccessCheckView(
                    entry.action(), entry.description(), entry.scope(), !sources.isEmpty(), sources));
        }
        result.sort(Comparator.comparing(AccessCheckView::action));
        return result;
    }

    /** Whether a grant at {@code scope} reaches the cluster for this permission, as the resolver decides it. */
    private static boolean reaches(
            Grant.ScopeType scope, UUID scopeId, CatalogueEntry entry, UUID clusterId, UUID environmentId) {
        return switch (scope) {
            case GLOBAL -> true;
            case ENVIRONMENT ->
                entry.scope() != PermissionScope.GLOBAL && environmentId != null && environmentId.equals(scopeId);
            case CLUSTER -> entry.scope() != PermissionScope.GLOBAL && clusterId != null && clusterId.equals(scopeId);
        };
    }

    /** The role a share gives its receiving team: the share whose owner, target and pattern this index entry is. */
    private String shareRoleName(TeamIndex.Shared share, Map<UUID, RoleEntity> roleById) {
        return shares.findByTargetTeamId(share.targetTeamId()).stream()
                .filter(s -> s.getOwnerTeamId().equals(share.ownerTeamId())
                        && s.getPattern().equals(share.pattern().text()))
                .map(s -> roleById.get(s.getRoleId()))
                .filter(java.util.Objects::nonNull)
                .map(RoleEntity::getName)
                .findFirst()
                .orElse("?");
    }

    private Set<String> permissionsOf(UUID roleId) {
        return rolePermissions.findByIdRoleId(roleId).stream()
                .map(RolePermissionEntity::getAction)
                .collect(Collectors.toSet());
    }

    /** Why the action cannot be exercised at this scope; null when it can. */
    private static String refusal(CatalogueEntry entry, Grant.ScopeType scope) {
        if (entry == null) {
            return NOT_CATALOGUED;
        }
        return entry.scope() == PermissionScope.GLOBAL && scope != Grant.ScopeType.GLOBAL ? GLOBAL_ONLY : null;
    }

    private static EffectivePermissionView view(
            String action,
            CatalogueEntry entry,
            Grant.ScopeType scope,
            UserRoleEntity row,
            RoleEntity role,
            String via) {
        String reason = refusal(entry, scope);
        return new EffectivePermissionView(
                action,
                entry == null ? null : entry.description(),
                scope.name(),
                scope == Grant.ScopeType.GLOBAL ? null : row.getScopeId(),
                role.getId(),
                role.getName(),
                via,
                reason == null,
                reason);
    }
}
