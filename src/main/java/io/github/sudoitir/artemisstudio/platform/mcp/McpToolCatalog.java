package io.github.sudoitir.artemisstudio.platform.mcp;

import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureRegistry;
import io.github.sudoitir.artemisstudio.kernel.plugin.McpToolDef;
import io.github.sudoitir.artemisstudio.kernel.plugin.McpToolDef.Posture;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The one description of the MCP surface (ADR-0054), assembled from the tools the enabled
 * modules declare in their descriptors (ADR-0070).
 *
 * <p>Everything the server says about itself is generated from here: the
 * {@code studio_help} tool, the {@code studio://tools} resource, and the
 * {@code instructions} block sent at initialisation. Before this existed the
 * instructions were retyped by hand in {@code application.yml} and had drifted —
 * they omitted {@code queue_lifecycle} and {@code message_body}, so the one text a
 * tool-searching host is guaranteed to read asserted, by omission, that two
 * capabilities did not exist. That is non-negotiable #5 broken by a duplicate, and
 * the fix is to have no duplicate.
 *
 * <p>{@code McpToolSchemaBudgetTest} fails the build when a registered tool is missing
 * from the catalogue, which is the same argument ADR-0045 made about listing size:
 * review does not catch drift, a build failure does.
 *
 * <p>This is documentation, not validation. Every discriminator is still checked in
 * {@link McpArgs} and every rejection names both the accepted values and
 * {@code studio_help}, so a model that reads none of this is inconvenienced, never
 * wrong.
 */
@Component
class McpToolCatalog {

    /** The discovery tool's own name, referenced from rejection messages. */
    static final String HELP_TOOL = "studio_help";

    private final List<McpToolDef> entries;

    McpToolCatalog(FeatureRegistry features) {
        this.entries =
                features.enabled().stream().flatMap(d -> d.mcpTools().stream()).toList();
    }

    List<McpToolDef> entries() {
        return entries;
    }

    List<String> toolNames() {
        return entries.stream().map(McpToolDef::name).toList();
    }

    /** One tool's detail, or {@code null} when the topic names nothing registered. */
    McpToolDef find(String tool) {
        return entries.stream()
                .filter(e -> e.name().equalsIgnoreCase(tool))
                .findFirst()
                .orElse(null);
    }

    /**
     * The compact index: what exists, grouped by posture, without any parameter
     * detail. This is what a model reads to choose a tool; it reads {@link #find}
     * only once it has chosen.
     */
    Map<String, List<Map<String, String>>> index() {
        Map<String, List<Map<String, String>>> out = new LinkedHashMap<>();
        for (Posture posture : Posture.values()) {
            out.put(
                    posture == Posture.READ ? "read" : "mutate",
                    entries.stream()
                            .filter(e -> e.posture() == posture)
                            .map(e -> Map.of("tool", e.name(), "summary", e.summary()))
                            .toList());
        }
        return out;
    }

    /**
     * The {@code instructions} sent at initialisation, generated rather than
     * retyped. Under a host that searches tools instead of dumping {@code
     * tools/list}, this is the only text guaranteed to be read, so it names every
     * tool and where the detail is.
     */
    String instructions() {
        String reads = entries.stream()
                .filter(e -> e.posture() == Posture.READ)
                .map(McpToolDef::name)
                .collect(Collectors.joining(", "));
        String mutations = entries.stream()
                .filter(e -> e.posture() == Posture.MUTATE)
                .map(McpToolDef::name)
                .collect(Collectors.joining(", "));
        return "Artemis Studio: cluster-wide management and observability for Apache ActiveMQ Artemis brokers. "
                + "Read tools: " + reads + ". "
                + "Mutating tools: " + mutations + " — all default to dryRun=true, and a real destructive run "
                + "needs `confirm` to equal the subject's name. "
                + "Call " + HELP_TOOL + " for the accepted values and JSON body shapes the tool schemas leave "
                + "out; the schemas are deliberately terse and " + HELP_TOOL + " is the complete reference. "
                + "Resources mirror the same detail for hosts that read them: studio://clusters (the source of "
                + "cluster ids this key can see), studio://permissions (what it may do), studio://tools, "
                + "cluster://{id}/topology, cluster://{id}/capabilities, "
                + "cluster://{id}/nodes/{nodeId}/settings. "
                + "Start from studio://clusters or " + HELP_TOOL + ".";
    }
}
