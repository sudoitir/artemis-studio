package io.github.sudoitir.artemisstudio.security.oidc;

import io.github.sudoitir.artemisstudio.config.ArtemisStudioProperties;
import io.github.sudoitir.artemisstudio.persist.OidcRoleMappingEntity;
import io.github.sudoitir.artemisstudio.persist.OidcRoleMappingRepository;
import io.github.sudoitir.artemisstudio.persist.RoleRepository;
import io.github.sudoitir.artemisstudio.persist.UserRoleEntity;
import io.github.sudoitir.artemisstudio.persist.UserRoleRepository;
import io.github.sudoitir.artemisstudio.security.ScopeIds;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Re-applies the configured claim → role mapping to a user's grants on every
 * OIDC login (oidc-sso spec, ADR-0040). A returning user's mapped grants track
 * the identity provider's current claim values, not just the values seen at
 * first login.
 *
 * <p>ponytail: a grant is treated as "OIDC-derived" if its (role, scope)
 * exactly matches a currently-configured mapping row — there is no separate
 * "derived by OIDC" flag on {@code user_role}. An administrator who happens to
 * hand-grant a user exactly the role/scope a mapping would also grant sees no
 * difference; the edge case where that grant should have survived an
 * unmapping is not distinguishable without a new column. Add one if this ever
 * bites someone.
 */
@Component
@RequiredArgsConstructor
public class ClaimRoleMapper {

    private final OidcRoleMappingRepository mappings;
    private final RoleRepository roles;
    private final UserRoleRepository userRoles;
    private final ArtemisStudioProperties properties;

    public enum Outcome {
        MAPPED,
        DEFAULTED,
        REFUSED
    }

    public Outcome apply(UUID userId, java.util.Map<String, Object> claims) {
        String claimName = properties.security().oidcClaim();
        List<String> claimValues = claimValues(claims.get(claimName));

        List<OidcRoleMappingEntity> allMappings = mappings.findByClaim(claimName);
        Set<UserRoleEntity.Key> desired = new HashSet<>();
        for (OidcRoleMappingEntity m : allMappings) {
            if (claimValues.contains(m.getClaimValue())) {
                desired.add(new UserRoleEntity(userId, m.getRoleId(), m.getScopeType(), m.getScopeId()).getId());
            }
        }

        boolean anyMapped = !desired.isEmpty();
        if (!anyMapped) {
            String defaultRole = properties.security().oidcDefaultRole();
            if (defaultRole == null) {
                return Outcome.REFUSED;
            }
            roles.findByName(defaultRole)
                    .ifPresent(role ->
                            desired.add(new UserRoleEntity(userId, role.getId(), "GLOBAL", ScopeIds.GLOBAL).getId()));
        }

        // Remove any grant this user holds that matches a mapping row but is no
        // longer desired (their claim values changed); leave every other grant alone.
        Set<UserRoleEntity.Key> allMappingKeys = new HashSet<>();
        for (OidcRoleMappingEntity m : allMappings) {
            allMappingKeys.add(new UserRoleEntity(userId, m.getRoleId(), m.getScopeType(), m.getScopeId()).getId());
        }
        for (UserRoleEntity existing : userRoles.findByIdUserId(userId)) {
            if (allMappingKeys.contains(existing.getId()) && !desired.contains(existing.getId())) {
                userRoles.deleteById(existing.getId());
            }
        }
        for (UserRoleEntity.Key key : desired) {
            if (userRoles.findById(key).isEmpty()) {
                userRoles.save(
                        new UserRoleEntity(key.getUserId(), key.getRoleId(), key.getScopeType(), key.getScopeId()));
            }
        }
        return anyMapped ? Outcome.MAPPED : Outcome.DEFAULTED;
    }

    private static List<String> claimValues(Object raw) {
        if (raw instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        if (raw instanceof String s) {
            return List.of(s);
        }
        return List.of();
    }
}
