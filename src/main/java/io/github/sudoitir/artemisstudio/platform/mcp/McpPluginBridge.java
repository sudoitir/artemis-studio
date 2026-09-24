package io.github.sudoitir.artemisstudio.platform.mcp;

import io.github.sudoitir.artemisstudio.kernel.plugin.McpToolDef;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginBridge;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginHandle;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginRuntimeStatus;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.descriptor.PluginDescriptor;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncPromptSpecification;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncResourceSpecification;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.annotation.McpPrompt;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.spring.SyncMcpAnnotationProviders;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Registers a plugin's own {@code @McpTool}/{@code @McpResource}/{@code @McpPrompt} beans with the
 * real, running {@link McpStatelessSyncServer} on activation, and removes them on deactivation
 * (design.md, task 6.5). The server is stateless and has no {@code list_changed}, so
 * {@link McpToolCatalog#addPlugin} is what keeps {@code studio_help} and {@code studio://tools}
 * current — every tool call goes through the catalogue's live reads, never a snapshot taken here.
 *
 * <p>Each tool's {@code callHandler} is wrapped so a call reaching a plugin mid Brief-maintenance
 * window gets the same {@code 503 plugin-updating} answer the gateway gives, and so every call runs
 * with the plugin's own thread context classloader set, counted as in-flight against its unload
 * drain — {@link PluginHandle#runInPlugin} is the one sanctioned way in.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class McpPluginBridge implements PluginBridge {

    /** Same ceiling {@code McpToolSchemaBudgetTest} enforces for every built-in tool. */
    private static final int PER_TOOL_TOKEN_BUDGET = 190;

    private final ObjectProvider<McpStatelessSyncServer> server;
    private final McpToolCatalog catalog;
    private final PluginRuntimeStatus runtimeStatus;
    private final JsonMapper json;

    private final Map<String, Registered> byPlugin = new ConcurrentHashMap<>();

    /**
     * {@code handle} is the owner this registration belongs to: the Instant activation class
     * attaches a new version before the old one detaches, so both briefly hold the same plugin id
     * here, and {@link #detach} must undo only the registration it itself made.
     */
    private record Registered(
            PluginHandle handle, List<String> toolNames, List<String> resourceUris, List<String> promptNames) {}

    @Override
    public void attach(PluginHandle handle) {
        catalog.addPlugin(handle.id(), toolCatalogueEntries(handle.descriptor()));

        McpStatelessSyncServer mcp = server.getIfAvailable();
        if (mcp == null) {
            // The MCP feature is disabled on this installation (spring.ai.mcp.server.enabled=false):
            // there is no server to register against, and nothing calls a plugin tool through it.
            byPlugin.put(handle.id(), new Registered(handle, List.of(), List.of(), List.of()));
            return;
        }

        String prefix = idSnake(handle.id()) + "_";
        List<Object> beans = mcpAnnotatedBeans(handle.applicationContext());

        List<SyncToolSpecification> tools = SyncMcpAnnotationProviders.statelessToolSpecifications(beans);
        for (SyncToolSpecification tool : tools) {
            if (!tool.tool().name().startsWith(prefix)) {
                throw new IllegalStateException("Plugin '" + handle.id() + "' registers MCP tool '"
                        + tool.tool().name() + "', which is not namespaced under '" + prefix + "'");
            }
            int cost = json.writeValueAsString(tool.tool()).length() / 4;
            if (cost > PER_TOOL_TOKEN_BUDGET) {
                throw new IllegalStateException(
                        "Plugin '" + handle.id() + "' tool '" + tool.tool().name()
                                + "' costs about " + cost + " tokens to describe, over the " + PER_TOOL_TOKEN_BUDGET
                                + "-token budget every tool must fit");
            }
        }
        List<SyncResourceSpecification> resources = SyncMcpAnnotationProviders.statelessResourceSpecifications(beans);
        List<SyncPromptSpecification> prompts = SyncMcpAnnotationProviders.statelessPromptSpecifications(beans);

        List<String> toolNames = new ArrayList<>();
        for (SyncToolSpecification tool : tools) {
            SyncToolSpecification wrapped = new SyncToolSpecification(
                    tool.tool(), (ctx, request) -> callThroughPlugin(handle, tool.callHandler(), ctx, request));
            mcp.addTool(wrapped);
            toolNames.add(tool.tool().name());
        }
        List<String> resourceUris = new ArrayList<>();
        for (SyncResourceSpecification resource : resources) {
            mcp.addResource(resource);
            resourceUris.add(resource.resource().uri());
        }
        List<String> promptNames = new ArrayList<>();
        for (SyncPromptSpecification prompt : prompts) {
            mcp.addPrompt(prompt);
            promptNames.add(prompt.prompt().name());
        }
        byPlugin.put(handle.id(), new Registered(handle, toolNames, resourceUris, promptNames));
    }

    /**
     * A no-op when {@code handle} is not the current owner of its plugin id: a newer version's
     * {@link #attach} already superseded it, and that newer registration (catalogue entry, MCP
     * tools/resources/prompts) must not be torn down by the old version's detach.
     */
    @Override
    public void detach(PluginHandle handle) {
        Registered registered = byPlugin.get(handle.id());
        if (registered == null || registered.handle() != handle || !byPlugin.remove(handle.id(), registered)) {
            return;
        }
        catalog.removePlugin(handle.id());
        McpStatelessSyncServer mcp = server.getIfAvailable();
        if (mcp == null) {
            return;
        }
        registered.toolNames().forEach(mcp::removeTool);
        registered.resourceUris().forEach(mcp::removeResource);
        registered.promptNames().forEach(mcp::removePrompt);
    }

    /**
     * Every call goes through here rather than the raw {@code callHandler}: an updating plugin
     * answers the same retry message the gateway would, and a live call is wrapped in
     * {@link PluginHandle#runInPlugin} so it counts as in-flight and runs with the plugin's own
     * TCCL. A listener that throws is logged and turned into an error result, never a protocol
     * failure — the same rule {@code McpErrors} applies to every built-in tool.
     */
    private io.modelcontextprotocol.spec.McpSchema.CallToolResult callThroughPlugin(
            PluginHandle handle,
            java.util.function.BiFunction<
                            io.modelcontextprotocol.common.McpTransportContext,
                            io.modelcontextprotocol.spec.McpSchema.CallToolRequest,
                            io.modelcontextprotocol.spec.McpSchema.CallToolResult>
                    callHandler,
            io.modelcontextprotocol.common.McpTransportContext ctx,
            io.modelcontextprotocol.spec.McpSchema.CallToolRequest request) {
        Duration retryAfter = runtimeStatus.updating(handle.id()).orElse(null);
        if (retryAfter != null) {
            return McpErrors.error("plugin " + handle.id() + " is updating, retry in " + retryAfter.toSeconds() + "s");
        }
        try {
            return handle.runInPlugin(() -> callHandler.apply(ctx, request));
        } catch (Exception e) {
            log.warn("Plugin '{}' MCP tool call failed", handle.id(), e);
            return McpErrors.error("That call failed inside plugin '" + handle.id() + "'.");
        }
    }

    private static List<McpToolDef> toolCatalogueEntries(PluginDescriptor descriptor) {
        return descriptor.mcpTools().stream()
                .map(t -> new McpToolDef(
                        t.name(),
                        McpToolDef.Posture.valueOf(t.posture().toUpperCase(Locale.ROOT)),
                        t.description(),
                        List.of()))
                .toList();
    }

    private static String idSnake(String pluginId) {
        return pluginId.replace('-', '_');
    }

    private static List<Object> mcpAnnotatedBeans(ApplicationContext ctx) {
        List<Object> beans = new ArrayList<>();
        for (String name : ctx.getBeanDefinitionNames()) {
            Class<?> type = ctx.getType(name);
            if (type != null && hasMcpAnnotatedMethod(type)) {
                beans.add(ctx.getBean(name));
            }
        }
        return beans;
    }

    private static boolean hasMcpAnnotatedMethod(Class<?> type) {
        for (Method method : type.getMethods()) {
            if (method.isAnnotationPresent(McpTool.class)
                    || method.isAnnotationPresent(McpResource.class)
                    || method.isAnnotationPresent(McpPrompt.class)) {
                return true;
            }
        }
        return false;
    }
}
