package io.github.sudoitir.artemisstudio.platform.clusters.web;

import io.github.sudoitir.artemisstudio.kernel.approval.web.HeldResponse;
import io.github.sudoitir.artemisstudio.kernel.core.PagedView;
import io.github.sudoitir.artemisstudio.kernel.core.ResourceQuery;
import io.github.sudoitir.artemisstudio.platform.clusters.EnvironmentService;
import io.github.sudoitir.artemisstudio.platform.clusters.web.EnvironmentViews.EnvironmentRequest;
import io.github.sudoitir.artemisstudio.platform.clusters.web.EnvironmentViews.EnvironmentView;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Environment CRUD and cluster assignment (environments spec). */
@RestController
@RequiredArgsConstructor
public class EnvironmentsController {

    private final EnvironmentService environments;

    @GetMapping("/environments")
    public PagedView<EnvironmentView> list(
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ResourceQuery.ofPage(page, size).paginate(environments.list(), null);
    }

    @HeldResponse
    @PostMapping("/environments")
    @ResponseStatus(HttpStatus.CREATED)
    public EnvironmentView create(@Valid @RequestBody EnvironmentRequest request) {
        return environments.create(request);
    }

    @HeldResponse
    @PutMapping("/environments/{environmentId}")
    public EnvironmentView update(@PathVariable UUID environmentId, @Valid @RequestBody EnvironmentRequest request) {
        return environments.update(environmentId, request);
    }

    @HeldResponse
    @DeleteMapping("/environments/{environmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID environmentId) {
        environments.delete(environmentId);
    }

    public record AssignEnvironmentRequest(UUID environmentId) {}

    @HeldResponse
    @PutMapping("/clusters/{clusterId}/environment")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void assign(@PathVariable UUID clusterId, @RequestBody AssignEnvironmentRequest request) {
        environments.assignCluster(clusterId, request.environmentId());
    }
}
