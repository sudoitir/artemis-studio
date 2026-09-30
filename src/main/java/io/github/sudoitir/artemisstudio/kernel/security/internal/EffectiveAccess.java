package io.github.sudoitir.artemisstudio.kernel.security.internal;

import io.github.sudoitir.artemisstudio.kernel.core.NotFoundException;
import io.github.sudoitir.artemisstudio.kernel.plugin.CatalogueEntry;
import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureRegistry;
import io.github.sudoitir.artemisstudio.kernel.security.Grant;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RolePermissionEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.UserRoleEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.UserRoleRepository;
import io.github.sudoitir.artemisstudio.kernel.security.web.UserViews.EffectivePermissionView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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

    /** Why the action cannot be exercised at this scope; null when it can. */
    private static String refusal(CatalogueEntry entry, Grant.ScopeType scope) {
        if (entry == null) {
            return NOT_CATALOGUED;
        }
        return entry.globalOnly() && scope != Grant.ScopeType.GLOBAL ? GLOBAL_ONLY : null;
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
