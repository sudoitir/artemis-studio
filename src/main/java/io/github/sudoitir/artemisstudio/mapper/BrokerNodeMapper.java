package io.github.sudoitir.artemisstudio.mapper;

import io.github.sudoitir.artemisstudio.domain.topology.NodeEndpoint;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeEntity;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** {@link BrokerNodeEntity} → {@link NodeEndpoint} (ADR-0014). */
@Mapper(config = CentralMapperConfig.class)
public interface BrokerNodeMapper {

    @Mapping(target = "active", expression = "java(Boolean.TRUE.equals(entity.getActive()))")
    @Mapping(target = "manageable", expression = "java(entity.getJolokiaUrl() != null)")
    NodeEndpoint toEndpoint(BrokerNodeEntity entity);

    List<NodeEndpoint> toEndpoints(List<BrokerNodeEntity> entities);
}
