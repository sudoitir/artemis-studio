package io.github.sudoitir.artemisstudio.mapper;

import io.github.sudoitir.artemisstudio.broker.BrokerCapabilities;
import io.github.sudoitir.artemisstudio.broker.BrokerCapabilities.CapabilityAssessment;
import io.github.sudoitir.artemisstudio.domain.topology.ClusterHealth;
import io.github.sudoitir.artemisstudio.domain.topology.ClusterTopology;
import io.github.sudoitir.artemisstudio.domain.topology.LogicalNode;
import io.github.sudoitir.artemisstudio.domain.topology.NodeEndpoint;
import io.github.sudoitir.artemisstudio.web.dto.ClusterViews.CapabilitiesView;
import io.github.sudoitir.artemisstudio.web.dto.ClusterViews.CapabilityView;
import io.github.sudoitir.artemisstudio.web.dto.ClusterViews.HealthView;
import io.github.sudoitir.artemisstudio.web.dto.ClusterViews.LogicalNodeView;
import io.github.sudoitir.artemisstudio.web.dto.ClusterViews.NodeEndpointView;
import io.github.sudoitir.artemisstudio.web.dto.ClusterViews.TopologyView;
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
