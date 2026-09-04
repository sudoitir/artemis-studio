package io.github.sudoitir.artemisstudio.web;

import io.github.sudoitir.artemisstudio.broker.BrokerConnectionException;
import io.github.sudoitir.artemisstudio.service.Attempt;
import io.github.sudoitir.artemisstudio.service.MessageAction;
import io.github.sudoitir.artemisstudio.service.MessageService;
import io.github.sudoitir.artemisstudio.service.MessageService.Outcome;
import io.github.sudoitir.artemisstudio.web.dto.MessageRequests.MessageActionRequest;
import io.github.sudoitir.artemisstudio.web.dto.MessageRequests.SendMessageRequest;
import io.github.sudoitir.artemisstudio.web.dto.MessageViews.AffectedView;
import io.github.sudoitir.artemisstudio.web.dto.MessageViews.DryRunView;
import io.github.sudoitir.artemisstudio.web.dto.MessageViews.MessageDetailView;
import io.github.sudoitir.artemisstudio.web.dto.MessageViews.MessagePageView;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Browse and act on one queue's messages (ADR-0020, ADR-0021). Every mutation
 * takes {@code ?dryRun=true} and returns a point-in-time {@link DryRunView}
 * without touching the broker; over the {@code safety.bulk-cap} a real run is a
 * {@code 422 bulk-cap-exceeded} unless {@code ?override=true}. Message I/O is
 * Jolokia-only and gated in the UI by the {@code messageIo} capability
 * (non-negotiable #5); a broker that refuses surfaces the classified
 * {@code BrokerConnectionException} through {@link ApiExceptionHandler}.
 */
@RestController
@RequestMapping("/api/v1/clusters/{clusterId}/queues/{queueName}/messages")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messages;

    @GetMapping
    public MessagePageView browse(
            @PathVariable UUID clusterId,
            @PathVariable String queueName,
            @RequestParam(required = false) UUID node,
            @RequestParam(required = false) String filter,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return messages.browse(clusterId, queueName, node, filter, Math.max(page, 1), Math.max(size, 1));
    }

    @GetMapping("/{messageId}")
    public MessageDetailView detail(
            @PathVariable UUID clusterId,
            @PathVariable String queueName,
            @PathVariable long messageId,
            @RequestParam(required = false) UUID node,
            @RequestParam(required = false) String filter) {
        return messages.detail(clusterId, queueName, messageId, node, filter);
    }

    @ApiResponse(
            responseCode = "200",
            content = @Content(schema = @Schema(anyOf = {AffectedView.class, DryRunView.class})))
    @PostMapping
    public ResponseEntity<Object> send(
            @PathVariable UUID clusterId,
            @PathVariable String queueName,
            @RequestParam(required = false) UUID node,
            @RequestParam(defaultValue = "false") boolean dryRun,
            @Valid @RequestBody SendMessageRequest request) {
        return respond(messages.send(clusterId, queueName, node, request, dryRun));
    }

    @ApiResponse(
            responseCode = "200",
            content = @Content(schema = @Schema(anyOf = {AffectedView.class, DryRunView.class})))
    @PostMapping("/actions/{action}")
    public ResponseEntity<Object> action(
            @PathVariable UUID clusterId,
            @PathVariable String queueName,
            @PathVariable String action,
            @RequestParam(required = false) UUID node,
            @RequestParam(defaultValue = "false") boolean dryRun,
            @RequestParam(defaultValue = "false") boolean override,
            @RequestBody MessageActionRequest request) {
        return respond(messages.execute(
                clusterId, queueName, node, MessageAction.fromPath(action), request, dryRun, override));
    }

    @ApiResponse(
            responseCode = "200",
            content = @Content(schema = @Schema(anyOf = {AffectedView.class, DryRunView.class})))
    @DeleteMapping
    public ResponseEntity<Object> purge(
            @PathVariable UUID clusterId,
            @PathVariable String queueName,
            @RequestParam(required = false) UUID node,
            @RequestParam(defaultValue = "false") boolean dryRun,
            @RequestParam(defaultValue = "false") boolean override) {
        return respond(messages.purge(clusterId, queueName, node, dryRun, override));
    }

    private static ResponseEntity<Object> respond(Attempt<Outcome> attempt) {
        Outcome outcome = unwrap(attempt);
        Object body =
                switch (outcome) {
                    case Outcome.Affected a -> new AffectedView(a.count(), false, a.node());
                    case Outcome.DryRun d -> new DryRunView(d.count(), d.cap(), d.overCap(), d.node());
                };
        return ResponseEntity.ok(body);
    }

    private static <T> T unwrap(Attempt<T> attempt) {
        return switch (attempt) {
            case Attempt.Ok<T> ok -> ok.value();
            case Attempt.Failed<T> failed -> throw new BrokerConnectionException(failed.kind(), failed.detail());
        };
    }
}
