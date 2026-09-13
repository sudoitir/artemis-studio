package io.github.sudoitir.artemisstudio.platform.clusters;

import io.github.sudoitir.artemisstudio.kernel.core.CentralMapperConfig;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerCapabilities;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerCapabilities.CapabilityAssessment;
import io.github.sudoitir.artemisstudio.platform.broker.NodeEndpoint;
import io.github.sudoitir.artemisstudio.platform.clusters.web.ClusterViews.CapabilitiesView;
import io.github.sudoitir.artemisstudio.platform.clusters.web.ClusterViews.CapabilityView;
import io.github.sudoitir.artemisstudio.platform.clusters.web.ClusterViews.HealthView;
import io.github.sudoitir.artemisstudio.platform.clusters.web.ClusterViews.LogicalNodeView;
import io.github.sudoitir.artemisstudio.platform.clusters.web.ClusterViews.NodeEndpointView;
import io.github.sudoitir.artemisstudio.platform.clusters.web.ClusterViews.TopologyView;
import org.mapstruct.Mapper;

/**
 * Domain → browser projections (ADR-0014). Enum-to-{@code String} conversions
 * ({@code SplitBrainStatus}, {@code CapabilityStatus}, {@code Level}) are
 * MapStruct defaults. {@code observedCycle} is intentionally not on
 * {@link NodeEndpointView} — an internal corroboration detail, not for the API.
 */
@Mapper(config = CentralMapperConfig.class)
public interface ClusterViewMapper {

    NodeEndpointView endpoint(NodeEndpoint endpoint);

    LogicalNodeView logicalNode(LogicalNode node);

    TopologyView topology(ClusterTopology topology);

    CapabilityView capability(CapabilityAssessment assessment);

    CapabilitiesView capabilities(BrokerCapabilities capabilities);

    HealthView health(ClusterHealth health);
}
