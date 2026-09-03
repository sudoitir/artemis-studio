package io.github.sudoitir.artemisstudio.mapper;

import static io.github.sudoitir.artemisstudio.broker.BrokerListOps.num;
import static io.github.sudoitir.artemisstudio.broker.BrokerListOps.str;

import io.github.sudoitir.artemisstudio.web.dto.ResourceViews.AddressView;
import io.github.sudoitir.artemisstudio.web.dto.ResourceViews.ConnectionView;
import io.github.sudoitir.artemisstudio.web.dto.ResourceViews.ConsumerView;
import io.github.sudoitir.artemisstudio.web.dto.ResourceViews.ProducerView;
import io.github.sudoitir.artemisstudio.web.dto.ResourceViews.SessionView;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * One Jolokia {@code listX} row (all-strings JSON, Phase 0) → its typed view,
 * tagged with the logical node it was fetched from. Hand-written rather than
 * MapStruct because the source is a loosely-typed {@link JsonNode}, not a bean.
 */
@Component
public class ResourceViewMapper {

    /** The logical node a fanned-out row belongs to. */
    public record NodeRef(UUID id, String name) {}

    public AddressView address(JsonNode row, NodeRef node) {
        return new AddressView(
                node.id(),
                node.name(),
                str(row, "name"),
                str(row, "routingTypes"),
                num(row, "queueCount"),
                num(row, "messageCount"));
    }

    public ConsumerView consumer(JsonNode row, NodeRef node) {
        return new ConsumerView(
                node.id(),
                node.name(),
                str(row, "id"),
                str(row, "session"),
                str(row, "queue"),
                str(row, "address"),
                str(row, "protocol"),
                num(row, "messagesDelivered"),
                num(row, "messagesAcknowledged"),
                str(row, "status"));
    }

    public SessionView session(JsonNode row, NodeRef node) {
        return new SessionView(
                node.id(),
                node.name(),
                str(row, "id"),
                str(row, "user"),
                str(row, "connectionID"),
                num(row, "consumerCount"),
                num(row, "producerCount"),
                str(row, "creationTime"));
    }

    public ConnectionView connection(JsonNode row, NodeRef node) {
        return new ConnectionView(
                node.id(),
                node.name(),
                str(row, "connectionID"),
                str(row, "remoteAddress"),
                str(row, "protocol"),
                str(row, "clientID"),
                num(row, "sessionCount"),
                str(row, "creationTime"));
    }

    public ProducerView producer(JsonNode row, NodeRef node) {
        return new ProducerView(
                node.id(),
                node.name(),
                str(row, "id"),
                str(row, "name"),
                str(row, "session"),
                str(row, "address"),
                str(row, "protocol"),
                num(row, "msgSent"));
    }
}
