package io.github.sudoitir.artemisstudio.platform.clusters;

import io.github.sudoitir.artemisstudio.platform.broker.ClockOffsetRegistry.ClockOffset;
import io.github.sudoitir.artemisstudio.platform.broker.NodeDirectory;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.BrokerNodeEntity;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.BrokerNodeRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
class ClusterNodeDirectory implements NodeDirectory {

    private final BrokerNodeRepository nodes;

    @Override
    @Transactional(readOnly = true)
    public List<KnownNode> nodes() {
        return nodes.findAll().stream()
                .map(n -> new KnownNode(n.getId(), n.getClusterId(), n.getName(), n.getJolokiaUrl()))
                .toList();
    }

    @Override
    @Transactional
    public void recordClockOffsets(Map<UUID, ClockOffset> byNode) {
        for (BrokerNodeEntity node : nodes.findAllById(byNode.keySet())) {
            ClockOffset offset = byNode.get(node.getId());
            node.recordClockOffset(offset.offsetMs(), offset.uncertaintyMs(), offset.measuredAt());
        }
    }
}
