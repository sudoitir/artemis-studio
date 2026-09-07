package io.github.sudoitir.artemisstudio.web;

import io.github.sudoitir.artemisstudio.broker.BrokerConnectionException;
import io.github.sudoitir.artemisstudio.service.Attempt;
import io.github.sudoitir.artemisstudio.service.ConnectionControlService;
import io.github.sudoitir.artemisstudio.service.ConnectionControlService.CloseResult;
import io.github.sudoitir.artemisstudio.web.dto.ConnectionViews.ConnectionCloseView;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Closing connections, sessions and an address's consumers (ADR-0057), beside the
 * cross-node views the targets are listed in.
 *
 * <p>The by-id routes name a <b>node</b>, which no other mutating route in Studio
 * does. That is not an oversight: a connection identifier is issued by one node
 * and means nothing on another, so a cluster-wide close by id would either do
 * nothing or hit an unrelated connection. Only the address-scoped close names the
 * cluster, and it fans out and reports per node.
 *
 * <p>Every route takes {@code ?dryRun=true}, which reads the target and reports
 * who would be disconnected — client id, user, sessions, consumers, and the
 * in-flight messages that would return to their queue — without closing anything.
 * The address-scoped close is additionally checked against {@code safety.bulk-cap}
 * and answers {@code 422 bulk-cap-exceeded} unless {@code ?override=true}.
 *
 * <p>A target that has already gone answers 200 with {@code alreadyGone}, not an
 * error: the requested state holds. Authorization goes through
 * {@code ClusterAccessGuard}, so a caller with no grant gets a 404 and cannot
 * learn that the cluster exists.
 */
@RestController
@RequestMapping("/api/v1/clusters/{clusterId}")
@RequiredArgsConstructor
public class ConnectionControlController {

    private final ConnectionControlService control;

    @PostMapping("/nodes/{nodeId}/connections/{connectionId}/close")
    public ConnectionCloseView closeConnection(
            @PathVariable UUID clusterId,
            @PathVariable UUID nodeId,
            @PathVariable String connectionId,
            @RequestParam(defaultValue = "false") boolean dryRun) {
        return respond(control.closeConnection(clusterId, nodeId, connectionId, dryRun));
    }

    @PostMapping("/nodes/{nodeId}/sessions/{sessionId}/close")
    public ConnectionCloseView closeSession(
            @PathVariable UUID clusterId,
            @PathVariable UUID nodeId,
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "false") boolean dryRun) {
        return respond(control.closeSession(clusterId, nodeId, sessionId, dryRun));
    }

    /**
     * Close the connection behind one consumer. A consumer row names its session
     * but not its connection, so the link is walked server-side — the client never
     * supplies the connection id it did not read.
     */
    @PostMapping("/nodes/{nodeId}/consumers/{consumerId}/close")
    public ConnectionCloseView closeConsumerConnection(
            @PathVariable UUID clusterId,
            @PathVariable UUID nodeId,
            @PathVariable String consumerId,
            @RequestParam(defaultValue = "false") boolean dryRun) {
        return respond(control.closeConsumerConnection(clusterId, nodeId, consumerId, dryRun));
    }

    @PostMapping("/addresses/{address}/consumers/close")
    public ConnectionCloseView closeAddressConsumers(
            @PathVariable UUID clusterId,
            @PathVariable String address,
            @RequestParam(defaultValue = "false") boolean dryRun,
            @RequestParam(defaultValue = "false") boolean override) {
        return respond(control.closeAddressConsumers(clusterId, address, dryRun, override));
    }

    private static ConnectionCloseView respond(Attempt<CloseResult> attempt) {
        return switch (attempt) {
            case Attempt.Ok<CloseResult> ok -> ConnectionCloseView.of(ok.value());
            case Attempt.Failed<CloseResult> failed ->
                throw new BrokerConnectionException(failed.kind(), failed.detail());
        };
    }
}
