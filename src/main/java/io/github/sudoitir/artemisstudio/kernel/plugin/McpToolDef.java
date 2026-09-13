package io.github.sudoitir.artemisstudio.kernel.plugin;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

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

    /** The {@code dryRun} note every mutating tool shares. */
    public static final String DRY_RUN = "Defaults to true. A dry run reports the affected count and changes nothing.";

    /** The {@code confirm} note every destructive tool shares. */
    public static final String CONFIRM =
            "Required to turn dryRun off on a destructive operation, and must equal the subject's name. "
                    + "This gate exists because the caller is a model: it stops an inferred or hallucinated "
                    + "name from becoming a real destruction.";

    /** The {@code override} note every bulk-capped tool shares. */
    public static final String OVERRIDE =
            "A separate gate from confirm: it answers \"this many messages really is intended\", against the "
                    + "server's bulk cap. Defaults to false and is never satisfied by confirm.";

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

        /** A discriminator whose accepted values are an enum's constants, lower-cased. */
        public static Param enumValues(String name, Enum<?>[] values, String note) {
            return values(
                    name,
                    Arrays.stream(values)
                            .map(v -> v.name().toLowerCase(Locale.ROOT))
                            .toList(),
                    note);
        }

        public static Param shape(String name, String shape, String note) {
            return new Param(name, null, shape, note);
        }

        public static Param note(String name, String note) {
            return new Param(name, null, null, note);
        }
    }
}
