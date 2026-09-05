package io.github.sudoitir.artemisstudio.web;

import io.github.sudoitir.artemisstudio.service.EnvironmentService;
import io.github.sudoitir.artemisstudio.web.dto.EnvironmentViews.EnvironmentRequest;
import io.github.sudoitir.artemisstudio.web.dto.EnvironmentViews.EnvironmentView;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Environment CRUD and cluster assignment (environments spec). */
@RestController
@RequiredArgsConstructor
public class EnvironmentsController {

    private final EnvironmentService environments;

    @GetMapping("/api/v1/environments")
    public List<EnvironmentView> list() {
        return environments.list();
    }

    @PostMapping("/api/v1/environments")
    @ResponseStatus(HttpStatus.CREATED)
    public EnvironmentView create(@Valid @RequestBody EnvironmentRequest request) {
        return environments.create(request);
    }

    @PutMapping("/api/v1/environments/{environmentId}")
    public EnvironmentView update(@PathVariable UUID environmentId, @Valid @RequestBody EnvironmentRequest request) {
        return environments.update(environmentId, request);
    }

    @DeleteMapping("/api/v1/environments/{environmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID environmentId) {
        environments.delete(environmentId);
    }

    public record AssignEnvironmentRequest(UUID environmentId) {}

    @PutMapping("/api/v1/clusters/{clusterId}/environment")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void assign(@PathVariable UUID clusterId, @RequestBody AssignEnvironmentRequest request) {
        environments.assignCluster(clusterId, request.environmentId());
    }
}
