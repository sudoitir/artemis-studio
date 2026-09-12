package io.github.sudoitir.artemisstudio.mcp;

import io.github.sudoitir.artemisstudio.broker.BrokerConnectionException;
import io.github.sudoitir.artemisstudio.service.BrokerConfigInvalidException;
import io.github.sudoitir.artemisstudio.service.BulkCapExceededException;
import io.github.sudoitir.artemisstudio.service.ConflictException;
import io.github.sudoitir.artemisstudio.service.HazardNotAcknowledgedException;
import io.github.sudoitir.artemisstudio.service.NotFoundException;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The MCP error contract (ADR-0045). {@code ApiExceptionHandler} is an
 * {@code @RestControllerAdvice} and never sees a tool invocation, so the mapping
 * from a service exception to something a model can act on lives here instead.
 *
 * <p>Two rules do the work:
 *
 * <ul>
 *   <li><b>Execution failures are results, not protocol errors.</b> A refused
 *       purge or an unreachable node is something the model can fix; it comes back
 *       as {@code isError: true} with a message naming the fix. Only a malformed
 *       call — unknown tool, bad argument, unknown resource URI — is a JSON-RPC
 *       {@code -32602}.
 *   <li><b>A denial's shape depends on whether there is an id to hide.</b> The
 *       {@code NotFoundException} thrown by {@code ClusterAccessGuard} must never
 *       name the missing permission: doing so would tell a caller with no grant
 *       that the cluster id exists, turning a deliberate information-hiding
 *       property into an enumeration oracle. An {@link AccessDeniedException} from
 *       a global {@code @PreAuthorize} has no id to hide, so it names the
 *       permission — honest capability gating (non-negotiable #5). Getting these
 *       two backwards is the failure this class exists to prevent;
 *       {@code McpClusterHidingTest} and {@code McpErrorMappingTest} guard it.
 * </ul>
 *
 * <p>A raw exception message or stack trace is never returned: it goes to the
 * model verbatim and may carry internals the caller has no business seeing.
 */
public final class McpErrors {

    /**
     * The one denial message for anything addressed by a cluster id. It names no
     * permission and does not confirm the id exists — both halves are load-bearing.
     */
    static final String CLUSTER_DENIED = "No such cluster, or this key has no grant on it.";

    private static final Logger LOG = LoggerFactory.getLogger(McpErrors.class);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private McpErrors() {}

    /**
     * Runs a tool body, turning any known failure into a {@code CallToolResult}
     * the model can act on. Every tool in this package goes through here.
     */
    static McpSchema.CallToolResult guard(Supplier<Object> body) {
        try {
            return ok(body.get());
        } catch (NotFoundException e) {
            // Deliberately not e.getMessage(): NotFoundException names the entity and
            // the id, which is exactly what must stay hidden here.
            return error(CLUSTER_DENIED);
        } catch (AccessDeniedException e) {
            return error("This key lacks the permission for that operation. "
                    + "Read studio://permissions to see what it holds.");
        } catch (BulkCapExceededException e) {
            return error("This would affect " + e.affectedCount() + " messages, over the safety cap of " + e.cap()
                    + ". Re-run with override=true to proceed, or narrow the filter to stay under the cap.");
        } catch (ConflictException e) {
            return error(e.getMessage());
        } catch (HazardNotAcknowledgedException e) {
            // The ids are the whole point: a model re-runs with exactly these.
            return error(e.getMessage() + " Pass them comma-separated in acknowledge.");
        } catch (BrokerConfigInvalidException e) {
            return error("The declaration is invalid: "
                    + e.violations().stream()
                            .map(v -> v.path() + ": " + v.message())
                            .collect(java.util.stream.Collectors.joining("; ")));
        } catch (BrokerConnectionException e) {
            return error("The broker could not be reached: " + e.kind().defaultMessage()
                    + " The cluster may be down, or its management URL may be wrong.");
        } catch (IllegalArgumentException e) {
            // A bad enum value or an out-of-range argument is a malformed call, not a
            // failure of the operation — the protocol has a code for that.
            throw invalidParams(e.getMessage());
        } catch (McpError e) {
            // Already the protocol's own shape (McpArgs raises these); it must not be
            // caught by the catch-all below.
            throw e;
        } catch (RuntimeException e) {
            // Anything unmapped is a bug, and its message is written for a log reader,
            // not for a model — it may name internals the caller has no business
            // seeing. The stack trace goes to the log; the caller gets a sentence it
            // can act on. Without this the raw exception text is what the model reads.
            LOG.error("Unmapped failure in an MCP tool", e);
            return error("That operation failed inside Studio. The server log has the detail; "
                    + "this is a bug rather than something the call can be corrected to avoid.");
        }
    }

    /** A JSON-RPC {@code -32602}: the call itself was malformed. */
    static McpError invalidParams(String message) {
        return McpError.builder(McpSchema.ErrorCodes.INVALID_PARAMS)
                .message(message)
                .build();
    }

    /**
     * A successful result: the typed projection as {@code structuredContent}, plus
     * the same JSON echoed as text for clients that do not read structured output.
     */
    static McpSchema.CallToolResult ok(Object value) {
        return McpSchema.CallToolResult.builder()
                .structuredContent(value)
                .addTextContent(json(value))
                .build();
    }

    static McpSchema.CallToolResult error(String message) {
        return McpSchema.CallToolResult.builder()
                .isError(true)
                .addTextContent(message)
                .build();
    }

    /** Parses a JSON-object argument. A malformed one is a malformed call, not a failed operation. */
    static <T> T parse(String field, String raw, Class<T> type) {
        try {
            return JSON.readValue(raw, type);
        } catch (RuntimeException e) {
            throw invalidParams(field + " must be a JSON object.");
        }
    }

    static String json(Object value) {
        return JSON.writeValueAsString(value);
    }
}
