package io.github.sudoitir.artemisstudio.web;

import io.github.sudoitir.artemisstudio.service.ConfigDiffService;
import io.github.sudoitir.artemisstudio.web.dto.ConfigViews.ConfigDiffView;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Broker configuration compared across two nodes of one cluster (ADR-0043).
 * Read-only introspection at the same permission tier as the topology read, and
 * deliberately unaudited — only mutating calls write an audit event.
 *
 * <p>With {@code left} omitted, the comparison defaults to the two endpoints of the
 * named node's logical node — the HA pair, which is where drift hurts.
 */
@RestController
@RequestMapping("/api/v1/clusters/{clusterId}/config-diff")
@RequiredArgsConstructor
public class ConfigDiffController {

    private final ConfigDiffService configDiff;

    @GetMapping
    public ConfigDiffView compare(
            @PathVariable UUID clusterId,
            @RequestParam(required = false) UUID left,
            @RequestParam(required = false) UUID right) {
        return configDiff.compare(clusterId, left, right);
    }
}
