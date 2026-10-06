package io.github.sudoitir.artemisstudio.kernel.security;

import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.GroupMappingRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.TeamPatternRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.TeamShareRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.UserRoleRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Grants scoped to something that is going away (ADR-0038). */
@Component
@RequiredArgsConstructor
public class ScopedGrants {

    private final UserRoleRepository userRoles;
    private final GroupMappingRepository groupMappings;
    private final TeamPatternRepository teamPatterns;
    private final TeamShareRepository teamShares;
    private final ApplicationEventPublisher events;

    /**
     * Drop every role assignment and group mapping scoped to {@code scopeId}, and for a cluster the
     * team patterns and shares on it, in the caller's transaction. Whoever else holds grants scoped to it, such
     * as an API token, drops them on {@link ScopeGrantsRevoked}.
     */
    @Transactional
    public void revoke(String scopeType, UUID scopeId) {
        userRoles.deleteByIdScopeTypeAndIdScopeId(scopeType, scopeId);
        groupMappings.deleteByScopeTypeAndScopeId(scopeType, scopeId);
        if (scopeType.equals("CLUSTER")) {
            teamPatterns.deleteByClusterId(scopeId);
            teamShares.deleteByClusterId(scopeId);
        }
        events.publishEvent(new ScopeGrantsRevoked(scopeType, scopeId));
    }
}
