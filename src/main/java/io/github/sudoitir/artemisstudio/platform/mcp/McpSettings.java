package io.github.sudoitir.artemisstudio.platform.mcp;

import io.github.sudoitir.artemisstudio.kernel.settings.SettingDef;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingDef.Kind;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsContribution;
import java.util.List;
import org.springframework.stereotype.Component;

/** The installation-wide read-only switch for the agent surface (ADR-0135). */
@Component
public class McpSettings implements SettingsContribution {

    public static final String READ_ONLY = "mcp.read-only";

    @Override
    public String featureId() {
        return "mcp";
    }

    @Override
    public List<SettingDef> settings() {
        return List.of(new SettingDef(
                READ_ONLY,
                "Agent surface",
                "Read-only",
                "When on, no API token can run a mutating MCP tool, and mutating tools are not listed.",
                Kind.BOOLEAN,
                () -> "false",
                null));
    }
}
