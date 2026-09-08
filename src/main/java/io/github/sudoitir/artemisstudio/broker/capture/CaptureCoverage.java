package io.github.sudoitir.artemisstudio.broker.capture;

import io.github.sudoitir.artemisstudio.persist.CaptureMode;
import io.github.sudoitir.artemisstudio.persist.CaptureState;
import io.github.sudoitir.artemisstudio.persist.MessageCaptureNodeEntity;
import io.github.sudoitir.artemisstudio.persist.MessageCaptureNodeRepository;
import io.github.sudoitir.artemisstudio.persist.MessageIndexSubscriptionEntity;
import io.github.sudoitir.artemisstudio.persist.MessageIndexSubscriptionRepository;
import io.github.sudoitir.artemisstudio.sql.QueueNamePattern;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Is this address being captured on this node, right now?" — the one question every
 * consumer of capture has to be able to ask before deciding to read a queue itself.
 *
 * <p>It is per node on purpose. A cluster-wide answer would tell the request-reply
 * sampler to stop browsing a node capture never reached, which is how a feature that
 * exists to see more ends up seeing less.
 */
@Component
@RequiredArgsConstructor
public class CaptureCoverage {

    private final MessageIndexSubscriptionRepository subscriptions;
    private final MessageCaptureNodeRepository captureNodes;

    @Transactional(readOnly = true)
    public boolean isCaptured(UUID clusterId, UUID nodeId, String address) {
        return subscriptions.findByClusterId(clusterId).stream()
                .filter(MessageIndexSubscriptionEntity::isEnabled)
                .filter(s -> s.getMode() == CaptureMode.CAPTURE)
                .filter(s -> QueueNamePattern.matches(s.getQueuePattern(), address))
                .anyMatch(s -> captureNodes
                        .findBySubscriptionIdAndNodeId(s.getId(), nodeId)
                        .map(MessageCaptureNodeEntity::getCaptureState)
                        .filter(state -> state == CaptureState.ACTIVE)
                        .isPresent());
    }
}
