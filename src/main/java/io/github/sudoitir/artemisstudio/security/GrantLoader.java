package io.github.sudoitir.artemisstudio.security;

import io.github.sudoitir.artemisstudio.persist.RolePermissionRepository;
import io.github.sudoitir.artemisstudio.persist.UserRoleEntity;
import io.github.sudoitir.artemisstudio.persist.UserRoleRepository;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Loads a user's {@code user_role} rows and unions each role's
 * {@code role_permission} actions into a {@link Grant} per (role, scope) pair.
 * Called once at authentication time (design.md decision 3), never per request.
 */
@Component
@RequiredArgsConstructor
public class GrantLoader {

    private final UserRoleRepository userRoles;
    private final RolePermissionRepository rolePermissions;

    public Set<Grant> loadFor(UUID userId) {
        List<UserRoleEntity> rows = userRoles.findByIdUserId(userId);
        Map<String, Set<String>> byScope = new HashMap<>();
        Map<String, Grant.ScopeType> scopeTypeByKey = new HashMap<>();
        Map<String, UUID> scopeIdByKey = new HashMap<>();
        for (UserRoleEntity row : rows) {
            String key = row.getScopeType() + "|" + row.getScopeId();
            scopeTypeByKey.put(key, Grant.ScopeType.valueOf(row.getScopeType()));
            scopeIdByKey.put(key, row.getScopeId());
            Set<String> actions = byScope.computeIfAbsent(key, k -> new HashSet<>());
            for (var rp : rolePermissions.findByIdRoleId(row.getRoleId())) {
                actions.add(rp.getAction());
            }
        }
        Set<Grant> grants = new HashSet<>();
        for (String key : byScope.keySet()) {
            grants.add(new Grant(scopeTypeByKey.get(key), scopeIdByKey.get(key), Set.copyOf(byScope.get(key))));
        }
        return grants;
    }
}
