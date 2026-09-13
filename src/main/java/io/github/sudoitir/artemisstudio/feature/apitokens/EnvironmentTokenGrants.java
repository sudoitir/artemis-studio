package io.github.sudoitir.artemisstudio.feature.apitokens;

import io.github.sudoitir.artemisstudio.feature.apitokens.internal.persistence.ApiTokenGrantRepository;
import io.github.sudoitir.artemisstudio.platform.clusters.EnvironmentRemoved;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** A token grant scoped to a deleted environment would grant nothing, so it goes with it. */
@Component
@RequiredArgsConstructor
class EnvironmentTokenGrants {

    private final ApiTokenGrantRepository grants;

    @EventListener
    void onEnvironmentRemoved(EnvironmentRemoved event) {
        grants.deleteByIdScopeTypeAndIdScopeId("ENVIRONMENT", event.environmentId());
    }
}
