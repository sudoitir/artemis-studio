package io.github.sudoitir.artemisstudio.platform.scrape;

import io.github.sudoitir.artemisstudio.kernel.security.ResourceNames;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** The queue and address names of the last scrape of a cluster, for the team pattern preview. */
@Component
@RequiredArgsConstructor
class ScrapedResourceNames implements ResourceNames {

    private final QueueSnapshots snapshots;

    @Override
    public List<String> queues(UUID clusterId) {
        return snapshots.forCluster(clusterId).stream()
                .map(QueueSnapshot::queueName)
                .distinct()
                .sorted()
                .toList();
    }

    @Override
    public List<String> addresses(UUID clusterId) {
        return snapshots.addresses(clusterId).stream().distinct().sorted().toList();
    }
}
