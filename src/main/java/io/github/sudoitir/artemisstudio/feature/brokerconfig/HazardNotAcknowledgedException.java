package io.github.sudoitir.artemisstudio.feature.brokerconfig;

import io.github.sudoitir.artemisstudio.platform.mcp.McpReportable;
import java.util.List;

/** A real run asked for without every High hazard acknowledged by its identifier (ADR-0067 D7). */
public class HazardNotAcknowledgedException extends RuntimeException implements McpReportable {

    private final List<String> missing;

    public HazardNotAcknowledgedException(List<String> missing) {
        super("Acknowledge " + missing.size() + (missing.size() == 1 ? " hazard" : " hazards") + " before applying: "
                + String.join(", ", missing));
        this.missing = List.copyOf(missing);
    }

    public List<String> missing() {
        return missing;
    }

    /** The ids are the whole point: a model re-runs with exactly these. */
    @Override
    public String mcpMessage() {
        return getMessage() + " Pass them comma-separated in acknowledge.";
    }
}
