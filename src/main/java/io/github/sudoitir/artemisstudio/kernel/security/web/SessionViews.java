package io.github.sudoitir.artemisstudio.kernel.security.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** A user's signed-in sessions and how to end them (ADR-0144). A session's own identifier is never shown. */
public final class SessionViews {

    private SessionViews() {}

    /**
     * @param handle names the session in {@code DELETE .../sessions/{handle}}; it is derived from the
     *     session identifier and cannot be turned back into it
     * @param lastActivityAt when the user last did something, not when the tab last polled
     * @param userAgent what the browser said it is, for the client to summarise; null when it sent none
     * @param current whether this is the session making the request
     */
    public record AccountSessionView(
            @Schema(requiredMode = REQUIRED) String handle,
            @Schema(requiredMode = REQUIRED) Instant signedInAt,
            @Schema(requiredMode = REQUIRED) Instant lastActivityAt,
            @Schema(nullable = true) String clientAddress,
            @Schema(nullable = true) String userAgent,
            @Schema(requiredMode = REQUIRED) boolean current) {}

    public record EndedSessionsView(
            @Schema(requiredMode = REQUIRED) int ended) {}
}
