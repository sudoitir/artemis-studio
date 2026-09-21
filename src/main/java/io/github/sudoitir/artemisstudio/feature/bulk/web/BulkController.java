package io.github.sudoitir.artemisstudio.feature.bulk.web;

import io.github.sudoitir.artemisstudio.feature.bulk.BulkService;
import io.github.sudoitir.artemisstudio.feature.bulk.web.BulkViews.BulkExecuteRequest;
import io.github.sudoitir.artemisstudio.feature.bulk.web.BulkViews.BulkPreviewRequest;
import io.github.sudoitir.artemisstudio.feature.bulk.web.BulkViews.BulkRunDetailView;
import io.github.sudoitir.artemisstudio.feature.bulk.web.BulkViews.BulkRunView;
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
 * Preview, execute, stop and read bulk runs (ADR-0093). Authorization goes through
 * {@code ClusterAccessGuard} inside {@link BulkService}, keyed on the single-queue
 * operation's own permission.
 */
@RestController
@RequestMapping("/api/v1/clusters/{clusterId}/bulk")
@RequiredArgsConstructor
public class BulkController {

    private final BulkService bulk;

    @PostMapping("/preview")
    @ResponseStatus(HttpStatus.CREATED)
    public BulkRunDetailView preview(@PathVariable UUID clusterId, @Valid @RequestBody BulkPreviewRequest request) {
        return bulk.preview(clusterId, request);
    }

    @PostMapping("/runs/{runId}/execute")
    public ResponseEntity<BulkRunView> execute(
            @PathVariable UUID clusterId, @PathVariable UUID runId, @Valid @RequestBody BulkExecuteRequest request) {
        return ResponseEntity.accepted().body(bulk.execute(clusterId, runId, request));
    }

    @PostMapping("/runs/{runId}/stop")
    public BulkRunView stop(@PathVariable UUID clusterId, @PathVariable UUID runId) {
        return bulk.stop(clusterId, runId);
    }

    @GetMapping("/runs")
    public List<BulkRunView> history(@PathVariable UUID clusterId) {
        return bulk.history(clusterId);
    }

    @GetMapping("/runs/{runId}")
    public BulkRunDetailView get(@PathVariable UUID clusterId, @PathVariable UUID runId) {
        return bulk.get(clusterId, runId);
    }
}
