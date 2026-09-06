package io.github.sudoitir.artemisstudio.mcp;

import io.github.sudoitir.artemisstudio.security.StudioPrincipal;
import io.github.sudoitir.artemisstudio.service.ClusterService;
import io.github.sudoitir.artemisstudio.web.dto.ClusterViews;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * The catalogue a client reads before it calls anything (ADR-0045).
 *
 * <p>Resources rather than tools, because these are things to look at, not
 * actions to take — and because a host can fetch a resource once and keep it,
 * where a tool call is paid for every time.
 *
 * <p>Two of them exist specifically so a model does not have to guess:
 * {@code studio://clusters} is the only honest source of cluster ids for this
 * key, and {@code studio://permissions} says what the key may do, so a refusal
 * later is predictable rather than surprising.
 */
@Component
@RequiredArgsConstructor
public class McpCatalogResources {

    private final ClusterService clusters;

    @McpResource(
            uri = "studio://clusters",
            name = "Clusters",
            description = "Every cluster this API key can see, with rolled-up health. The source of cluster ids.",
            mimeType = "application/json")
    public McpSchema.ReadResourceResult clusters() {
        // ClusterService.list() is @PostFilter'd on cluster:read, so this is already
        // narrowed to what the key may see — there is no unfiltered listing to leak.
        List<McpViews.ClusterEntry> entries = clusters.list().stream()
                .map(c -> new McpViews.ClusterEntry(
                        c.id(), c.name(), c.health().name(), c.nodeCount(), c.environmentId()))
                .sorted(Comparator.comparing(McpViews.ClusterEntry::cluster))
                .toList();
        return json("studio://clusters", entries);
    }

    @McpResource(
            uri = "studio://permissions",
            name = "Key permissions",
            description = "What this API key can do, and where. Read it before assuming an operation will work.",
            mimeType = "application/json")
    public McpSchema.ReadResourceResult permissions() {
        StudioPrincipal principal = principal();
        List<McpViews.GrantEntry> grants = principal.grants().stream()
                .map(g -> new McpViews.GrantEntry(
                        g.scopeType().name(),
                        g.scopeId(),
                        g.permissions().stream().sorted().toList()))
                .sorted(Comparator.comparing(McpViews.GrantEntry::scope)
                        .thenComparing(e -> String.valueOf(e.scopeId())))
                .toList();
        // These are the grants after intersection with the owner's live grants, so the
        // answer stays true as the owner is promoted or demoted (ADR-0046).
        return json(
                "studio://permissions",
                new McpViews.TokenPermissions(principal.getUsername(), principal.tokenName(), grants));
    }

    @McpResource(
            uri = "cluster://{clusterId}/topology",
            name = "Cluster topology",
            description = "The HA pairs and endpoints of one cluster, with each node's id and role.",
            mimeType = "application/json")
    public McpSchema.ReadResourceResult topology(String clusterId) {
        UUID id = McpArgs.uuid("clusterId", clusterId);
        ClusterViews.TopologyView view = clusters.topology(id);
        return json("cluster://" + clusterId + "/topology", view);
    }

    /**
     * Per non-negotiable #5, a capability the connection cannot reach comes back
     * <em>named</em>, with the exact {@code broker.xml} that would enable it. A
     * silently absent capability would leave a model concluding the product cannot
     * do something, when the truth is that this broker is not configured for it.
     */
    @McpResource(
            uri = "cluster://{clusterId}/capabilities",
            name = "Cluster capabilities",
            description = "Which operations this cluster's connection supports, and the broker.xml "
                    + "needed to enable any it does not.",
            mimeType = "application/json")
    public McpSchema.ReadResourceResult capabilities(String clusterId) {
        UUID id = McpArgs.uuid("clusterId", clusterId);
        ClusterViews.CapabilitiesView c = clusters.capabilities(id);
        List<McpViews.CapabilityEntry> entries = new ArrayList<>();
        entries.add(entry("managementRead", c.managementRead()));
        entries.add(entry("managementWrite", c.managementWrite()));
        entries.add(entry("notifications", c.notifications()));
        entries.add(entry("messageIo", c.messageIo()));
        entries.add(entry("slowConsumerDetection", c.slowConsumerDetection()));
        return json("cluster://" + clusterId + "/capabilities", entries);
    }

    private static McpViews.CapabilityEntry entry(String name, ClusterViews.CapabilityView v) {
        return new McpViews.CapabilityEntry(name, v.status(), v.reason(), v.brokerXmlSnippet());
    }

    private static StudioPrincipal principal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof StudioPrincipal p) {
            return p;
        }
        // Unreachable through the filter chain, which requires authentication on /mcp;
        // an empty principal here would mean the security context did not survive the
        // transport, and failing loudly beats reporting "you hold nothing".
        throw new IllegalStateException("no authenticated principal on the MCP request thread");
    }

    private static McpSchema.ReadResourceResult json(String uri, Object value) {
        return new McpSchema.ReadResourceResult(
                List.of(new McpSchema.TextResourceContents(uri, "application/json", McpErrors.json(value))));
    }
}
