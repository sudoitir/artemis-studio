package io.github.sudoitir.artemisstudio.service;

import io.github.sudoitir.artemisstudio.broker.core.BrokerEvent;
import io.github.sudoitir.artemisstudio.broker.core.BrokerEventSink;
import io.github.sudoitir.artemisstudio.domain.rr.Observation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Maps {@code activemq.notifications} events to lifecycle {@link Observation}s
 * for {@link RrCorrelator} (design.md D2): responder presence and temp-queue
 * teardown. Notifications about {@code activemq.notifications} itself — a
 * subscriber's own non-durable queue — are filtered out (Phase 5 spike,
 * docs/broker-management-notes.md §13): they are Studio's own plumbing, never
 * request-reply traffic.
 *
 * <p>{@code activemq.notifications} fires consumer/binding/delivery events for
 * <em>every</em> address on the broker, not just traced ones — every branch
 * below checks relevance against the correlator before forwarding, otherwise
 * ordinary broker-wide traffic would flood {@code rr_flow} with irrelevant rows
 * and the in-memory responder tracker with entries for addresses nobody asked
 * Studio to trace.
 *
 * <p>{@code MESSAGE_DELIVERED} on a temp-queue address closes the loop for the
 * temporary-reply-queue pattern: the sampler never polls a per-request temp
 * queue (it has no fixed name to poll), so a delivery to it is the only signal
 * a reply arrived. The destination alone identifies the flow — the temp queue
 * is 1:1 per request — so no correlation id is needed for that observation.
 */
@Component
@RequiredArgsConstructor
public class RrNotificationObserver implements BrokerEventSink {

    private static final String NOTIFICATIONS_ADDRESS = "activemq.notifications";

    private final RrCorrelator correlator;

    @Override
    public void accept(BrokerEvent event) {
        if (NOTIFICATIONS_ADDRESS.equals(event.address())) {
            return;
        }
        switch (event.type()) {
            case "CONSUMER_CREATED" -> {
                if (correlator.isTracedRequestAddress(event.clusterId(), event.address())) {
                    correlator.accept(new Observation.ResponderUp(
                            event.clusterId(),
                            event.nodeId(),
                            event.occurredAt(),
                            event.address(),
                            event.consumerName()));
                }
            }
            case "CONSUMER_CLOSED" -> {
                if (correlator.isTracedRequestAddress(event.clusterId(), event.address())) {
                    correlator.accept(new Observation.ResponderDown(
                            event.clusterId(),
                            event.nodeId(),
                            event.occurredAt(),
                            event.address(),
                            event.consumerName(),
                            consumerCount(event)));
                }
            }
            case "BINDING_REMOVED" -> {
                if (correlator.hasOpenTempQueueFlow(event.clusterId(), event.routingName())) {
                    correlator.accept(new Observation.TempQueueUnbound(
                            event.clusterId(), event.nodeId(), event.occurredAt(), event.routingName()));
                }
            }
            case "MESSAGE_DELIVERED" -> {
                if (correlator.hasOpenTempQueueFlow(event.clusterId(), event.routingName())) {
                    correlator.accept(new Observation.ReplySeen(
                            event.clusterId(),
                            event.nodeId(),
                            event.occurredAt(),
                            event.routingName(),
                            messageId(event),
                            null,
                            null,
                            java.util.Map.of()));
                }
            }
            case "MESSAGE_EXPIRED" ->
                correlator.accept(new Observation.MessageExpired(
                        event.clusterId(), event.nodeId(), event.occurredAt(), event.address(), messageId(event)));
            default -> {
                // Every other notification type is retained by BrokerEventWriter but carries no rr_flow signal.
            }
        }
    }

    private static int consumerCount(BrokerEvent event) {
        Object v = event.props().get("_AMQ_ConsumerCount");
        return v instanceof Number n ? n.intValue() : 0;
    }

    private static String messageId(BrokerEvent event) {
        Object v = event.props().get("_AMQ_Message_ID");
        return v == null ? null : v.toString();
    }
}
