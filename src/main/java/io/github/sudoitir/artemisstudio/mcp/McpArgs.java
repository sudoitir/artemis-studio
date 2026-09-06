package io.github.sudoitir.artemisstudio.mcp;

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
 */
final class McpArgs {

    private McpArgs() {}

    static UUID uuid(String field, String value) {
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
    static UUID optionalUuid(String field, String value) {
        return value == null || value.isBlank() ? null : uuid(field, value);
    }

    static <E extends Enum<E>> E enumOf(Class<E> type, String field, String value, E fallback) {
        if (value == null || value.isBlank()) {
            if (fallback != null) {
                return fallback;
            }
            throw McpErrors.invalidParams(field + " is required. One of: " + names(type));
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw McpErrors.invalidParams(field + " must be one of: " + names(type) + ".");
        }
    }

    static boolean flag(Boolean value, boolean whenAbsent) {
        return value == null ? whenAbsent : value;
    }

    static String required(String field, String value) {
        if (value == null || value.isBlank()) {
            throw McpErrors.invalidParams(field + " is required.");
        }
        return value.trim();
    }

    private static <E extends Enum<E>> String names(Class<E> type) {
        return Arrays.stream(type.getEnumConstants())
                .map(e -> e.name().toLowerCase(Locale.ROOT))
                .collect(Collectors.joining(", "));
    }
}
