package io.github.sudoitir.artemisstudio.platform.mcp;

import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Argument coercion for the MCP tool surface.
 *
 * <p>Tool parameters are flat scalars on purpose (ADR-0045) — a model fills a
 * string reliably and a nested object badly — so ids arrive as strings and
 * discriminators as free text. Everything is validated here rather than in the
 * JSON schema: a long enum spelled out in the schema is paid for on every
 * {@code tools/list}, while a rejected value costs one round trip and the error
 * message can say what the valid values are.
 *
 * <p>A bad argument is a malformed call, so it becomes a JSON-RPC
 * {@code -32602}, never a failed operation.
 *
 * <p>Every rejection that names its accepted values also names {@code studio_help}
 * (ADR-0054), so a wrong guess becomes a recovery rather than a retry loop: the
 * model learns where the whole reference is, not just the one value it missed.
 */
public final class McpArgs {

    private McpArgs() {}

    /** Appended to every rejection that names accepted values (ADR-0054). */
    private static final String SEE_HELP = "Call " + McpToolCatalog.HELP_TOOL + " for the full reference.";

    public static UUID uuid(String field, String value) {
        if (value == null || value.isBlank()) {
            throw McpErrors.invalidParams(field + " is required.");
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            throw McpErrors.invalidParams(field + " must be a UUID. Read studio://clusters for the ids this key sees.");
        }
    }

    /** Optional id: absent stays absent, present must parse. */
    public static UUID optionalUuid(String field, String value) {
        return value == null || value.isBlank() ? null : uuid(field, value);
    }

    public static <E extends Enum<E>> E enumOf(Class<E> type, String field, String value, E fallback) {
        if (value == null || value.isBlank()) {
            if (fallback != null) {
                return fallback;
            }
            throw McpErrors.invalidParams(field + " is required. One of: " + names(type) + ". " + SEE_HELP);
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw McpErrors.invalidParams(field + " must be one of: " + names(type) + ". " + SEE_HELP);
        }
    }

    public static boolean flag(Boolean value, boolean whenAbsent) {
        return value == null ? whenAbsent : value;
    }

    public static String required(String field, String value) {
        if (value == null || value.isBlank()) {
            throw McpErrors.invalidParams(field + " is required.");
        }
        return value.trim();
    }

    /**
     * A duration the way an operator says it ({@code 15m}, {@code 6h}, {@code 2d}), not
     * ISO-8601. Models write the former reliably and {@code PT6H} unreliably. Defaults to 1h.
     */
    public static Duration window(String window) {
        if (window == null || window.isBlank()) {
            return Duration.ofHours(1);
        }
        String w = window.trim().toLowerCase(Locale.ROOT);
        char unit = w.charAt(w.length() - 1);
        long amount;
        try {
            amount = Long.parseLong(w.substring(0, w.length() - 1));
        } catch (NumberFormatException e) {
            throw McpErrors.invalidParams("window must look like 15m, 6h or 2d.");
        }
        if (amount <= 0) {
            throw McpErrors.invalidParams("window must be positive.");
        }
        return switch (unit) {
            case 's' -> Duration.ofSeconds(amount);
            case 'm' -> Duration.ofMinutes(amount);
            case 'h' -> Duration.ofHours(amount);
            case 'd' -> Duration.ofDays(amount);
            default -> throw McpErrors.invalidParams("window unit must be s, m, h or d.");
        };
    }

    /**
     * The model-facing gate. It is deliberately an exact match on the subject's own name:
     * anything looser (a yes, a boolean) is something a model will produce without having
     * read the name it is about to destroy.
     */
    public static void confirm(String subject, String confirm) {
        if (!subject.equals(confirm)) {
            throw McpErrors.invalidParams("A real run needs confirm to be exactly \"" + subject
                    + "\". Re-run with dryRun=true to see what it would affect first.");
        }
    }

    private static <E extends Enum<E>> String names(Class<E> type) {
        return Arrays.stream(type.getEnumConstants())
                .map(e -> e.name().toLowerCase(Locale.ROOT))
                .collect(Collectors.joining(", "));
    }
}
