package io.github.sudoitir.artemisstudio.web;

import io.github.sudoitir.artemisstudio.broker.BrokerConnectionException;
import io.github.sudoitir.artemisstudio.service.Attempt;
import io.github.sudoitir.artemisstudio.service.LifecycleOutcome;
import io.github.sudoitir.artemisstudio.service.LifecycleOutcome.NodeStatus;
import io.github.sudoitir.artemisstudio.service.QueueLifecycleService;
import io.github.sudoitir.artemisstudio.web.dto.LifecycleRequests.CreateAddressRequest;
import io.github.sudoitir.artemisstudio.web.dto.LifecycleRequests.CreateQueueRequest;
import io.github.sudoitir.artemisstudio.web.dto.LifecycleRequests.UpdateQueueRequest;
import io.github.sudoitir.artemisstudio.web.dto.LifecycleViews.LifecycleOutcomeView;
import io.github.sudoitir.artemisstudio.web.dto.LifecycleViews.NodeOutcomeView;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Create, destroy, reconfigure, pause and resume queues, and create and destroy
 * addresses, across a whole cluster (ADR-0049).
 *
 * <p>Every route names a cluster, never a node: Artemis cluster nodes each own
 * their own queues, so the operation fans out to every live node and the response
 * is a per-node outcome list. Every mutating route takes {@code ?dryRun=true},
 * which reports the target nodes — and, for a destroy, the messages that would be
 * lost on each — without touching any broker. A destroy over the
 * {@code safety.bulk-cap} is a {@code 422 bulk-cap-exceeded} unless
 * {@code ?override=true}, because destroying a queue destroys its messages and
 * that is a bulk destructive operation whatever the endpoint is called.
 *
 * <p>Authorization goes through {@code ClusterAccessGuard}, so a caller with no
 * grant on the cluster gets a 404 and cannot learn that the cluster exists.
 */
@RestController
@RequestMapping("/api/v1/clusters/{clusterId}")
@RequiredArgsConstructor
public class QueueLifecycleController {

    private final QueueLifecycleService lifecycle;

    // ---- queues ---------------------------------------------------------

    @PostMapping("/queues")
    public LifecycleOutcomeView createQueue(
            @PathVariable UUID clusterId,
            @RequestParam(defaultValue = "false") boolean dryRun,
            @Valid @RequestBody CreateQueueRequest request) {
        return respond(lifecycle.createQueue(clusterId, request, dryRun));
    }

    @PatchMapping("/queues/{queueName}")
    public LifecycleOutcomeView updateQueue(
            @PathVariable UUID clusterId,
            @PathVariable String queueName,
            @RequestParam(defaultValue = "false") boolean dryRun,
            @Valid @RequestBody UpdateQueueRequest request) {
        return respond(lifecycle.updateQueue(clusterId, queueName, request, dryRun));
    }

    @DeleteMapping("/queues/{queueName}")
    public LifecycleOutcomeView deleteQueue(
            @PathVariable UUID clusterId,
            @PathVariable String queueName,
            @RequestParam(defaultValue = "false") boolean dryRun,
            @RequestParam(defaultValue = "false") boolean override) {
        return respond(lifecycle.deleteQueue(clusterId, queueName, dryRun, override));
    }

    @PostMapping("/queues/{queueName}/pause")
    public LifecycleOutcomeView pauseQueue(
            @PathVariable UUID clusterId,
            @PathVariable String queueName,
            @RequestParam(defaultValue = "false") boolean dryRun) {
        return respond(lifecycle.setPaused(clusterId, queueName, true, dryRun));
    }

    @PostMapping("/queues/{queueName}/resume")
    public LifecycleOutcomeView resumeQueue(
            @PathVariable UUID clusterId,
            @PathVariable String queueName,
            @RequestParam(defaultValue = "false") boolean dryRun) {
        return respond(lifecycle.setPaused(clusterId, queueName, false, dryRun));
    }

    @PostMapping("/queues/{queueName}/reset-counter")
    public LifecycleOutcomeView resetCounter(
            @PathVariable UUID clusterId,
            @PathVariable String queueName,
            @RequestParam(defaultValue = "false") boolean dryRun) {
        return respond(lifecycle.resetCounter(clusterId, queueName, dryRun));
    }

    // ---- addresses ------------------------------------------------------

    @PostMapping("/addresses")
    public LifecycleOutcomeView createAddress(
            @PathVariable UUID clusterId,
            @RequestParam(defaultValue = "false") boolean dryRun,
            @Valid @RequestBody CreateAddressRequest request) {
        return respond(lifecycle.createAddress(clusterId, request, dryRun));
    }

    /**
     * Delete an address. Force-free by design (D8): one that still has queues bound
     * is refused with a message naming them, and the operator destroys those
     * explicitly. One click that destroys an unbounded amount of data with no
     * per-queue count in the confirmation is not a safe default.
     */
    @DeleteMapping("/addresses/{address}")
    public LifecycleOutcomeView deleteAddress(
            @PathVariable UUID clusterId,
            @PathVariable String address,
            @RequestParam(defaultValue = "false") boolean dryRun) {
        return respond(lifecycle.deleteAddress(clusterId, address, dryRun));
    }

    // ---- mapping --------------------------------------------------------

    private static LifecycleOutcomeView respond(Attempt<LifecycleOutcome> attempt) {
        LifecycleOutcome outcome =
                switch (attempt) {
                    case Attempt.Ok<LifecycleOutcome> ok -> ok.value();
                    case Attempt.Failed<LifecycleOutcome> failed ->
                        throw new BrokerConnectionException(failed.kind(), failed.detail());
                };
        return new LifecycleOutcomeView(
                outcome.dryRun(),
                outcome.cap(),
                outcome.overCap(),
                isPartial(outcome),
                outcome.totalAffected(),
                outcome.nodes().stream()
                        .map(n -> new NodeOutcomeView(
                                n.nodeId(), n.nodeName(), n.status().name(), n.affected(), n.error()))
                        .toList());
    }

    /**
     * Whether the command landed unevenly: at least one node changed or was already
     * in the requested state, and at least one did not receive it or refused it.
     * Computed here rather than in the UI so every client agrees on what "partial"
     * means.
     */
    private static boolean isPartial(LifecycleOutcome outcome) {
        if (outcome.dryRun()) {
            return false;
        }
        boolean anySettled = outcome.nodes().stream()
                .anyMatch(n -> n.status() == NodeStatus.APPLIED || n.status() == NodeStatus.ALREADY);
        boolean anyNot = outcome.nodes().stream()
                .anyMatch(n -> n.status() == NodeStatus.FAILED || n.status() == NodeStatus.SKIPPED_NOT_LIVE);
        return anySettled && anyNot;
    }
}
