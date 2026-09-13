package io.github.sudoitir.artemisstudio.kernel.plugin.api;

import java.util.List;

/**
 * The catalogue entry for one MCP tool (ADR-0054). The catalogue is documentation
 * generated into {@code studio_help}, the tools resource and the server
 * instructions; every argument is still validated by the tool itself.
 *
 * @param name the tool name, exactly as registered
 * @param summary one line, for the index
 * @param params the detail the schema deliberately leaves out
 */
public record McpToolDef(String name, Posture posture, String summary, List<Param> params) {

    /** Whether a tool reads or changes something — the axis tools may not be grouped across. */
    public enum Posture {
        READ,
        MUTATE
    }

    /**
     * @param values the values it accepts, when it is a discriminator
     * @param shape the JSON body shape, when it takes one
     * @param note anything a model gets wrong without being told
     */
    public record Param(String name, List<String> values, String shape, String note) {

        public static Param values(String name, List<String> values, String note) {
            return new Param(name, values, null, note);
        }

        public static Param shape(String name, String shape, String note) {
            return new Param(name, null, shape, note);
        }

        public static Param note(String name, String note) {
            return new Param(name, null, null, note);
        }
    }
}
