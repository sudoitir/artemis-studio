package io.github.sudoitir.rahdar.feature.apitokens;

import io.github.sudoitir.rahdar.feature.apitokens.internal.persistence.ApiTokenGrantRepository;
import io.github.sudoitir.rahdar.kernel.security.ScopeGrantsRevoked;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** A token grant scoped to a deleted environment or cluster would grant nothing, so it goes with it. */
@Component
@RequiredArgsConstructor
class ScopedTokenGrants {

    private final ApiTokenGrantRepository grants;

    @EventListener
    void onScopeGrantsRevoked(ScopeGrantsRevoked event) {
        grants.deleteByIdScopeTypeAndIdScopeId(event.scopeType(), event.scopeId());
    }
}
