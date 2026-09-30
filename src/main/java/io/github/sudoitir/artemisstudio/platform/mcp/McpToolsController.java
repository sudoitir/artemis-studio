package io.github.sudoitir.artemisstudio.platform.mcp;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The MCP tools a key can be restricted to (ADR-0135): every registered tool, the plugins' included,
 * for the allow-list picker on the account page. Whether a key may then use one is still its grants'
 * decision.
 */
@RestController
@RequestMapping("/api/v1/mcp/tools")
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
    public List<McpToolView> list() {
        return catalog.entries().stream()
                .filter(e -> !McpToolCatalog.HELP_TOOL.equals(e.name()))
                .map(e -> new McpToolView(e.name(), e.posture().name(), e.summary()))
                .toList();
    }
}
