package io.github.sudoitir.artemisstudio.scheduler;

import io.github.sudoitir.artemisstudio.domain.rr.RrState;
import io.github.sudoitir.artemisstudio.persist.RrEventEntity;
import io.github.sudoitir.artemisstudio.persist.RrEventRepository;
import io.github.sudoitir.artemisstudio.persist.RrFlowEntity;
import io.github.sudoitir.artemisstudio.persist.RrFlowRepository;
import io.github.sudoitir.artemisstudio.sse.SseHub;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves flows past their deadline out of {@code AWAITING_REPLY} (design.md D4):
 * {@code ORPHANED} when no responder was ever observed for the request address,
 * {@code TIMED_OUT} otherwise. Neither state comes from a single observation —
 * "nothing happened for long enough" is a fact only a sweep can produce.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RrDeadlineSweep {

    private final RrFlowRepository flows;
    private final RrEventRepository events;
    private final SseHub sseHub;

    /**
     * Scheduled by {@link DynamicSchedules} on {@code rr.sweep-interval}. It used to
     * hardcode 5s and ignore the configured value entirely.
     */
    @Transactional
    public void sweep() {
        Instant now = Instant.now();
        List<RrFlowEntity> overdue = flows.findByStateAndDeadlineAtBefore(RrState.AWAITING_REPLY.name(), now);
        for (RrFlowEntity flow : overdue) {
            boolean hadResponder = flow.getResponderConsumer() != null;
            RrState next = hadResponder ? RrState.TIMED_OUT : RrState.ORPHANED;
            flow.setState(next.name());
            flow.setObservedAt(now);
            flows.save(flow);
            events.save(new RrEventEntity(flow.getId(), flow.getNodeId(), next.name(), now, null));
            sseHub.publish(flow.getClusterId(), "rr");
        }
        if (!overdue.isEmpty()) {
            log.debug("Deadline sweep moved {} flows out of AWAITING_REPLY", overdue.size());
        }
    }
}
