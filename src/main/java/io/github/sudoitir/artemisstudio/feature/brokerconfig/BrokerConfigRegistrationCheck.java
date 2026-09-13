package io.github.sudoitir.artemisstudio.feature.brokerconfig;

import io.github.sudoitir.artemisstudio.feature.brokerconfig.web.BrokerConfigViews.RecommendationsView;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerCapabilities;
import io.github.sudoitir.artemisstudio.platform.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.platform.clusters.RegistrationCheckContributor;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The recommended configuration a registration check previews (ADR-0068), so the recommendations
 * shown before registration are the same ones shown after it: seeded from what the checked node is
 * running, not generic.
 */
@Component
@RequiredArgsConstructor
class BrokerConfigRegistrationCheck implements RegistrationCheckContributor {

    private final BrokerConfigOperations operations;

    @Override
    public String featureId() {
        return BrokerConfigModule.DESCRIPTOR.id();
    }

    @Override
    public RecommendationsView contribute(BrokerCapabilities capabilities, JolokiaBrokerClient seed) {
        return RecommendationsView.of(BrokerConfigRecommendations.from(capabilities, observe(seed)));
    }

    /**
     * A read that fails costs the seed and nothing else: the panel then says it could not read the
     * node rather than showing a replace nobody can check.
     */
    private ObservedNodeConfig observe(JolokiaBrokerClient seed) {
        try {
            return operations.read(
                    seed,
                    new UUID(0, 0),
                    "the checked node",
                    new BrokerConfigOperations.ReadScope(
                            Set.of("#"), Set.of("#", "activemq.notifications"), Set.of(), Map.of(), Set.of()));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
