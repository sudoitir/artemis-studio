package io.github.sudoitir.artemisstudio.web.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The broker-events history API (ADR-0026, ADR-0028). */
public final class EventViews {

    private EventViews() {}

    public record BrokerEventView(
            @Schema(requiredMode = REQUIRED) long seq,
            @Schema(requiredMode = REQUIRED) Instant occurredAt,
            @Schema(requiredMode = REQUIRED) Instant receivedAt,
            @Schema(requiredMode = REQUIRED) String type,
            @Schema(nullable = true) String address,
            @Schema(nullable = true) String routingName,
            @Schema(nullable = true) String consumerName,
            @Schema(nullable = true) String sessionName,
            @Schema(nullable = true) String connectionName,
            @Schema(nullable = true) String remoteAddress,
            @Schema(nullable = true) String username,
            @Schema(nullable = true) UUID nodeId,
            @Schema(nullable = true) Map<String, Object> props) {}

    public record BrokerEventPageView(
            @Schema(requiredMode = REQUIRED) List<BrokerEventView> data,
            @Schema(requiredMode = REQUIRED) long count,
            @Schema(requiredMode = REQUIRED) int page,
            @Schema(requiredMode = REQUIRED) int pageSize,
            @Schema(requiredMode = REQUIRED) long dropped,
            @Schema(nullable = true) Instant oldestRetained) {}
}
