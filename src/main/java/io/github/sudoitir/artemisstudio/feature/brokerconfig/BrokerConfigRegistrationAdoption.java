package io.github.sudoitir.artemisstudio.feature.brokerconfig;

import io.github.sudoitir.artemisstudio.kernel.security.PermissionResolver;
import io.github.sudoitir.artemisstudio.platform.clusters.RegistrationAdoption;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Adoption as a step of registering a cluster (ADR-0176): the registration check previews what the
 * running configuration would declare, and a confirmed registration saves it as revision 1 in its own
 * transaction. It is {@link BrokerConfigService#adopt} and {@link BrokerConfigService#save} as the
 * configuration view uses them, so what is previewed at registration is what the view would offer.
 */
@Component
@RequiredArgsConstructor
class BrokerConfigRegistrationAdoption implements RegistrationAdoption {

    private final BrokerConfigService config;
    private final PermissionResolver permissions;

    @Override
    public Optional<Preview> preview(List<LiveNode> nodes) {
        // Only an operator who may declare configuration is offered the adoption: the cluster does not exist
        // yet, so the check is the permission as a whole, not a grant on the cluster.
        if (!permissions.can(BrokerConfigPermissions.CONFIG_WRITE)) {
            return Optional.empty();
        }
        return config.previewAdoption(nodes).map(a -> {
            BrokerConfigDocument d = a.document();
            return new Preview(
                    new Counts(
                            d.addresses().size(),
                            d.addressSettings().size(),
                            d.securitySettings().size(),
                            d.diverts().size()),
                    a.disagreements(),
                    a.notes());
        });
    }

    @Override
    public void adopt(UUID clusterId) {
        BrokerConfigService.Adoption adoption = config.adopt(clusterId);
        config.save(
                clusterId,
                adoption.document(),
                null,
                "Adopted from the running cluster when it was registered",
                BrokerConfigService.Source.ADOPT);
    }
}
