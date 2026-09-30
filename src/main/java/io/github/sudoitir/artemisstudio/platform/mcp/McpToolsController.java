package io.github.sudoitir.artemisstudio.platform.mcp;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.github.sudoitir.artemisstudio.kernel.core.PagedView;
import io.github.sudoitir.artemisstudio.kernel.core.ResourceQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The MCP tools a key can be restricted to (ADR-0137): every registered tool, the plugins' included,
 * for the allow-list picker on the account page. Whether a key may then use one is still its grants'
 * decision.
 */
@RestController
@RequestMapping("/mcp/tools")
@RequiredArgsConstructor
public class McpToolsController {

    private final McpToolCatalog catalog;

    public record McpToolView(
            @Schema(requiredMode = REQUIRED) String name,

            @Schema(
                    requiredMode = REQUIRED,
                    allowableValues = {"READ", "MUTATE"})
            String posture,

            @Schema(requiredMode = REQUIRED) String summary) {}

    @GetMapping
    public PagedView<McpToolView> list(
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ResourceQuery.ofPage(page, size)
                .paginate(
                        catalog.entries().stream()
                                .filter(e -> !McpToolCatalog.HELP_TOOL.equals(e.name()))
                                .map(e -> new McpToolView(e.name(), e.posture().name(), e.summary()))
                                .toList(),
                        null);
    }
}
