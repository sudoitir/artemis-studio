package io.github.sudoitir.artemisstudio.kernel.security;

import io.github.sudoitir.artemisstudio.kernel.security.internal.OidcRoleMappingRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.UserRoleRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Grants scoped to something that is going away (ADR-0038). */
@Component
@RequiredArgsConstructor
public class ScopedGrants {

    private final UserRoleRepository userRoles;
    private final OidcRoleMappingRepository oidcMappings;

    /** Drop every role assignment and group mapping scoped to {@code scopeId}, in the caller's transaction. */
    @Transactional
    public void revoke(String scopeType, UUID scopeId) {
        userRoles.deleteByIdScopeTypeAndIdScopeId(scopeType, scopeId);
        oidcMappings.findAllByOrderByClaimAscClaimValueAsc().stream()
                .filter(m -> scopeType.equals(m.getScopeType()) && scopeId.equals(m.getScopeId()))
                .forEach(m -> oidcMappings.deleteById(m.getId()));
    }
}
