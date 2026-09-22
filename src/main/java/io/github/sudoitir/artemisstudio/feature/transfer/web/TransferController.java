package io.github.sudoitir.artemisstudio.feature.transfer.web;

import io.github.sudoitir.artemisstudio.feature.transfer.TransferService;
import io.github.sudoitir.artemisstudio.feature.transfer.web.TransferViews.OrphanReturnRequest;
import io.github.sudoitir.artemisstudio.feature.transfer.web.TransferViews.OrphanReturnView;
import io.github.sudoitir.artemisstudio.feature.transfer.web.TransferViews.OrphanView;
import io.github.sudoitir.artemisstudio.feature.transfer.web.TransferViews.TransferExecuteRequest;
import io.github.sudoitir.artemisstudio.feature.transfer.web.TransferViews.TransferPreviewRequest;
import io.github.sudoitir.artemisstudio.feature.transfer.web.TransferViews.TransferRunView;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Preview, run, stop, resume, return and read message transfers (ADR-0097). The path's cluster is the
 * source for a preview, and the source or the target for a run. Authorization is on both clusters,
 * through {@code ClusterAccessGuard} inside {@link TransferService}.
 */
@RestController
@RequestMapping("/api/v1/clusters/{clusterId}/transfers")
@RequiredArgsConstructor
public class TransferController {

    private final TransferService transfers;

    @PostMapping("/preview")
    @ResponseStatus(HttpStatus.CREATED)
    public TransferRunView preview(@PathVariable UUID clusterId, @Valid @RequestBody TransferPreviewRequest request) {
        return transfers.preview(clusterId, request);
    }

    @PostMapping("/runs/{runId}/execute")
    public ResponseEntity<TransferRunView> execute(
            @PathVariable UUID clusterId,
            @PathVariable UUID runId,
            @Valid @RequestBody TransferExecuteRequest request) {
        return ResponseEntity.accepted().body(transfers.execute(clusterId, runId, request));
    }

    @PostMapping("/runs/{runId}/stop")
    public TransferRunView stop(@PathVariable UUID clusterId, @PathVariable UUID runId) {
        return transfers.stop(clusterId, runId);
    }

    @PostMapping("/runs/{runId}/resume")
    public ResponseEntity<TransferRunView> resume(@PathVariable UUID clusterId, @PathVariable UUID runId) {
        return ResponseEntity.accepted().body(transfers.resume(clusterId, runId));
    }

    @PostMapping("/runs/{runId}/return")
    public ResponseEntity<TransferRunView> returnToSource(@PathVariable UUID clusterId, @PathVariable UUID runId) {
        return ResponseEntity.accepted().body(transfers.returnToSource(clusterId, runId));
    }

    @GetMapping("/runs")
    public List<TransferRunView> history(@PathVariable UUID clusterId) {
        return transfers.history(clusterId);
    }

    @GetMapping("/runs/{runId}")
    public TransferRunView get(@PathVariable UUID clusterId, @PathVariable UUID runId) {
        return transfers.get(clusterId, runId);
    }

    @GetMapping("/orphans")
    public List<OrphanView> orphans(@PathVariable UUID clusterId) {
        return transfers.orphans(clusterId);
    }

    @PostMapping("/orphans/return")
    public OrphanReturnView returnOrphan(
            @PathVariable UUID clusterId, @Valid @RequestBody OrphanReturnRequest request) {
        return transfers.returnOrphan(clusterId, request);
    }
}
