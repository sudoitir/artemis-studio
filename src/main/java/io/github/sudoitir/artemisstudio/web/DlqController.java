package io.github.sudoitir.artemisstudio.web;

import io.github.sudoitir.artemisstudio.service.DlqService;
import io.github.sudoitir.artemisstudio.web.dto.MessageViews.DlqView;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dead-letter / expiry management (ADR-0021, D8). The addresses come from the
 * broker's own address settings — when that read fails the response says so
 * ({@code settingsAvailable = false}) rather than guessing from names.
 */
@RestController
@RequestMapping("/api/v1/clusters/{clusterId}/dlq")
@RequiredArgsConstructor
public class DlqController {

    private final DlqService dlq;

    @GetMapping
    public DlqView view(@PathVariable UUID clusterId) {
        return dlq.view(clusterId);
    }
}
