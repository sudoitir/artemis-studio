package io.github.sudoitir.artemisstudio.web.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.sudoitir.artemisstudio.broker.ConnectionOperations.ConnectionSnapshot;
import io.github.sudoitir.artemisstudio.service.ConnectionCloseKind;
import io.github.sudoitir.artemisstudio.service.ConnectionControlService.CloseResult;
import io.github.sudoitir.artemisstudio.web.dto.LifecycleViews.LifecycleOutcomeView;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The connection-control API's responses (ADR-0057).
 *
 * <p>A close answers with two things, and the second is the reason this is not
 * just a {@code LifecycleOutcomeView}: what was found immediately before the
 * close. An operator confirms against a client identifier they recognise, not the
 * opaque connection identifier, and the confirmation has to state how much is
 * about to be disconnected — so the preview carries the identity, the counts, and
 * the in-flight message consequence.
 */
public final class ConnectionViews {

    private ConnectionViews() {}

    /**
     * What is about to be disconnected, as the broker reported it a moment ago.
     *
     * @param confirmToken what the UI asks the operator to type — the client id, or
     *     the remote address when the broker reports no client id. Computed here so
     *     the API and every client agree on it.
     * @param messagesInTransit in-flight messages that will return to their queue
     *     with an increased delivery count. Null means the broker did not report it,
     *     which a client must state rather than render as zero.
     */
    public record ConnectionTargetView(
            @Schema(requiredMode = REQUIRED) String connectionId,
            @Schema(nullable = true) String clientId,
            @Schema(nullable = true) String remoteAddress,
            @Schema(nullable = true) String user,
            @Schema(nullable = true) String protocol,
            @Schema(requiredMode = REQUIRED) long sessionCount,
            @Schema(requiredMode = REQUIRED) long consumerCount,
            @Schema(nullable = true) Long messagesInTransit,
            @Schema(requiredMode = REQUIRED) String confirmToken) {

        static ConnectionTargetView of(ConnectionSnapshot s) {
            return new ConnectionTargetView(
                    s.connectionId(),
                    s.clientId(),
                    s.remoteAddress(),
                    s.user(),
                    s.protocol(),
                    s.sessionCount(),
                    s.consumerCount(),
                    s.messagesInTransit(),
                    s.label());
        }
    }

    /**
     * One close's result.
     *
     * @param alreadyGone the target was not there to close. A success: the
     *     requested state — that connection is not open — holds. A client renders
     *     this where it renders a success, never as an error.
     * @param target null for an address-scoped close, which has no single
     *     application to name, and for a target that was already gone
     */
    public record ConnectionCloseView(
            @Schema(
                    requiredMode = REQUIRED,
                    allowableValues = {"CONNECTION", "SESSION", "CONSUMER", "ADDRESS_CONSUMERS"})
            String kind,

            @Schema(requiredMode = REQUIRED, description = "The id or address the close was aimed at.")
            String subject,

            @Schema(requiredMode = REQUIRED) boolean alreadyGone,
            /**
             * Omitted rather than sent as null: springdoc renders a {@code $ref}
             * without a nullable marker, so a null on the wire would contradict the
             * generated types. Absent means there was nothing to describe.
             */
            @JsonInclude(JsonInclude.Include.NON_NULL) ConnectionTargetView target,
            @Schema(requiredMode = REQUIRED) LifecycleOutcomeView outcome) {

        public static ConnectionCloseView of(CloseResult result) {
            return new ConnectionCloseView(
                    result.kind().name(),
                    result.subject(),
                    // A preview of a target that has already gone is already gone too:
                    // the operator asked about a row the broker no longer has, and
                    // saying so is more useful than an empty estimate.
                    result.kind() != ConnectionCloseKind.ADDRESS_CONSUMERS && result.target() == null,
                    result.target() == null ? null : ConnectionTargetView.of(result.target()),
                    LifecycleOutcomeView.of(result.outcome()));
        }
    }
}
