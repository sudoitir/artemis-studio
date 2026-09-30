package io.github.sudoitir.artemisstudio.platform.clusters;

import io.github.sudoitir.artemisstudio.kernel.core.CentralMapperConfig;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerCapabilities;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerCapabilities.CapabilityAssessment;
import io.github.sudoitir.artemisstudio.platform.broker.NodeEndpoint;
import io.github.sudoitir.artemisstudio.platform.broker.VersionGate;
import io.github.sudoitir.artemisstudio.platform.clusters.web.ClusterViews.CapabilitiesView;
import io.github.sudoitir.artemisstudio.platform.clusters.web.ClusterViews.CapabilityView;
import io.github.sudoitir.artemisstudio.platform.clusters.web.ClusterViews.HealthView;
import io.github.sudoitir.artemisstudio.platform.clusters.web.ClusterViews.LogicalNodeView;
import io.github.sudoitir.artemisstudio.platform.clusters.web.ClusterViews.NodeEndpointView;
import io.github.sudoitir.artemisstudio.platform.clusters.web.ClusterViews.NodeVersionView;
import io.github.sudoitir.artemisstudio.platform.clusters.web.ClusterViews.TopologyView;
import io.github.sudoitir.artemisstudio.platform.clusters.web.ClusterViews.VersionGateView;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Domain → browser projections (ADR-0014). Enum-to-{@code String} conversions
 * ({@code SplitBrainStatus}, {@code CapabilityStatus}, {@code Level}) are
 * MapStruct defaults. {@code observedCycle} is intentionally not on
 * {@link NodeEndpointView} — an internal corroboration detail, not for the API.
 */
@Mapper(config = CentralMapperConfig.class)
public interface ClusterViewMapper {

    @Mapping(target = "versionSupport", expression = "java(BrokerVersion.support(endpoint.version()))")
    NodeEndpointView endpoint(NodeEndpoint endpoint);

    LogicalNodeView logicalNode(LogicalNode node);

    TopologyView topology(ClusterTopology topology);

    CapabilityView capability(CapabilityAssessment assessment);

    CapabilitiesView capabilities(BrokerCapabilities capabilities, List<VersionGate.Assessment> versionGates);

    @Mapping(target = "brokerXmlSnippet", ignore = true)
    VersionGateView versionGate(VersionGate.Assessment assessment);

    NodeVersionView nodeVersion(VersionGate.NodeVerdict verdict);

    HealthView health(ClusterHealth health);
}
