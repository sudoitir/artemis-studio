package io.github.sudoitir.artemisstudio.kernel.lifecycle.web;

import io.github.sudoitir.artemisstudio.kernel.lifecycle.LifecycleService;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.StorageHealthService;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.web.DataViews.HealthResponse;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.web.DataViews.PreviewRequest;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.web.DataViews.PreviewResponse;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.web.DataViews.StoreView;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.web.DataViews.StoresResponse;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.web.DataViews.TableView;
import io.github.sudoitir.artemisstudio.kernel.lifecycle.web.DataViews.UpdatePolicyRequest;
import jakarta.validation.Valid;
import java.util.NoSuchElementException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Retention, quotas and storage health ({@code /api/v1/data}, ADR-0134). An out-of-bounds policy
 * is a {@code 400} naming the allowed range; an unknown store a {@code 404}.
 */
@RestController
@RequestMapping("/data")
@RequiredArgsConstructor
public class DataController {

    private final LifecycleService lifecycle;
    private final StorageHealthService health;

    @GetMapping("/stores")
    public StoresResponse stores() {
        return new StoresResponse(lifecycle.stores().stream().map(StoreView::of).toList());
    }

    @PutMapping("/stores/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void update(@PathVariable String id, @Valid @RequestBody UpdatePolicyRequest request) {
        lifecycle.update(id, request.retention(), request.quota(), request.quotaWarnPercent());
    }

    @PostMapping("/stores/{id}/preview")
    public PreviewResponse preview(@PathVariable String id, @Valid @RequestBody PreviewRequest request) {
        return PreviewResponse.of(lifecycle.preview(id, request.retention()));
    }

    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse(health.tables().stream().map(TableView::of).toList());
    }

    @ExceptionHandler(NoSuchElementException.class)
    ProblemDetail notFound(NoSuchElementException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }
}
