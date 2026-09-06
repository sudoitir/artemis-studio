package io.github.sudoitir.artemisstudio.mcp;

import io.github.sudoitir.artemisstudio.security.StudioPrincipal;
import io.github.sudoitir.artemisstudio.service.ClusterService;
import io.github.sudoitir.artemisstudio.service.ConfigDiffService;
import io.github.sudoitir.artemisstudio.web.dto.ClusterViews;
import io.github.sudoitir.artemisstudio.web.dto.ConfigViews;
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
    private final ConfigDiffService configDiff;

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

    /**
     * The detail the tool schemas deliberately leave out — mirrored here for hosts
     * that read resources (ADR-0054).
     *
     * <p>This is a mirror, not the route. {@code resources} is an optional server
     * capability and nothing in the protocol obliges a client ever to call
     * {@code resources/read}, so ADR-0050's decision to put this detail here and
     * only here made the surface unusable on hosts that skip resources. The
     * {@code studio_help} tool is now the primary channel, because tools are the one
     * part of MCP every host implements; this resource is generated from the same
     * {@link McpToolCatalog} and nothing depends on it being fetched.
     */
    @McpResource(
            uri = "studio://tools",
            name = "Tool parameter detail",
            description = "Accepted values and JSON body shapes for every tool. Mirrors the studio_help tool.",
            mimeType = "application/json")
    public McpSchema.ReadResourceResult toolDetail() {
        return json("studio://tools", McpToolCatalog.entries());
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

    /**
     * One node's effective broker settings, as a resource rather than a tool.
     *
     * <p>A resource because this is something to look at, not an action to take —
     * and because a host fetches a resource once and keeps it, where a tool is paid
     * for in the listing on every conversation whether or not anyone asks about
     * settings (ADR-0050). Node ids come from {@code cluster://{id}/topology}.
     *
     * <p>These are the settings the node is <em>running with</em>, resolved by the
     * broker. Studio never reads or writes {@code broker.xml}, and this does not
     * mutate anything.
     */
    @McpResource(
            uri = "cluster://{clusterId}/nodes/{nodeId}/settings",
            name = "Node settings",
            description = "One node's effective broker configuration: broker attributes, address settings, "
                    + "security settings and acceptors, as the broker resolves them.",
            mimeType = "application/json")
    public McpSchema.ReadResourceResult nodeSettings(String clusterId, String nodeId) {
        UUID id = McpArgs.uuid("clusterId", clusterId);
        UUID node = McpArgs.uuid("nodeId", nodeId);
        ConfigViews.NodeConfigView view = configDiff.nodeConfig(id, node);
        return json("cluster://" + clusterId + "/nodes/" + nodeId + "/settings", view);
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
