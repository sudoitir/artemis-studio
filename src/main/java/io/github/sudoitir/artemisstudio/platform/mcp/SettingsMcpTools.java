package io.github.sudoitir.artemisstudio.platform.mcp;

import io.github.sudoitir.artemisstudio.kernel.settings.SettingsService;
import io.github.sudoitir.artemisstudio.kernel.settings.web.SettingsViews.SettingValue;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * The {@code studio_setting} MCP tool. It stays in the MCP module: the kernel's settings
 * module must not depend on MCP. *
 * <p>Mutating tools (ADR-0045). They go through the same services the REST layer calls, so
 * authorization, the bulk cap and the audit row are the existing ones; this class adds only
 * the model-facing gate: {@code dryRun} defaults to true, a real destructive run needs
 * {@code confirm} to equal the subject's name, and {@code override} is the separate bulk-cap
 * escape that {@code confirm} never satisfies.
 */
@Component
@RequiredArgsConstructor
public class SettingsMcpTools {

    private final SettingsService settings;

    public enum SettingOp {
        GET,
        SET
    }

    @McpTool(
            name = "studio_setting",
            description =
                    "Read or change an operational setting: scrape cadence, rate limit, " + "retention, bulk cap.",
            annotations =
                    @McpTool.McpAnnotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    public McpSchema.CallToolResult studioSetting(
            @McpToolParam(required = false) String op,
            @McpToolParam(required = false) String key,
            @McpToolParam(required = false) String value) {
        SettingOp operation = McpArgs.enumOf(SettingOp.class, "op", op, SettingOp.GET);
        return McpErrors.guard(() -> {
            Map<String, SettingValue> effective = settings.effective();
            if (operation == SettingOp.SET) {
                String k = McpArgs.required("key", key);
                settings.put(k, McpArgs.required("value", value));
                effective = settings.effective();
                return entry(k, effective.get(k));
            }
            if (key != null && !key.isBlank()) {
                return entry(key.trim(), effective.get(key.trim()));
            }
            List<McpViews.SettingEntry> all = new ArrayList<>();
            effective.forEach((k, v) -> all.add(entry(k, v)));
            return all;
        });
    }

    private static McpViews.SettingEntry entry(String key, SettingValue value) {
        if (value == null) {
            throw McpErrors.invalidParams("Unknown setting key: " + key + ".");
        }
        return new McpViews.SettingEntry(key, value.value(), value.overridden());
    }
}
