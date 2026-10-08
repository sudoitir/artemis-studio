package io.github.sudoitir.artemisstudio.kernel.approval;

import io.github.sudoitir.artemisstudio.kernel.gate.HeldState;
import io.github.sudoitir.artemisstudio.kernel.inbox.InboxService;
import io.github.sudoitir.artemisstudio.kernel.inbox.Notice;
import io.github.sudoitir.artemisstudio.kernel.inbox.Notice.Severity;
import io.github.sudoitir.artemisstudio.kernel.stream.UserSignals;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Tells people about held requests, inside the transaction that changed them (ADR-0182): approvers get an inbox item
 * when a request is held, which is retitled and marked read once it is decided or ends; the requester gets one for
 * every outcome. Each change also nudges the requester's open streams with a {@code held} signal.
 */
@Component
class ApprovalNotices {

    /** The inbox source of every approval notice. */
    static final String SOURCE = ApprovalModule.ID;

    private final InboxService inbox;
    private final UserSignals signals;

    ApprovalNotices(InboxService inbox, UserSignals signals) {
        this.inbox = inbox;
        this.signals = signals;
    }

    /** A request was held: every eligible approver is asked to decide it. */
    void held(HeldRow row, Collection<UUID> approvers, Instant now) {
        String body =
                "Requested by " + row.requesterUsername() + (row.reason() == null ? "." : ": “" + row.reason() + "”");
        Duration ttl = Duration.between(now, row.expiresAt());
        inbox.post(
                SOURCE,
                new Notice(
                        "approval.requested",
                        Severity.WARNING,
                        title("Approval needed: ", row.summary()),
                        body,
                        row.link(),
                        approverKey(row.id()),
                        Map.of("heldOperation", row.id().toString()),
                        ttl.isNegative() || ttl.isZero() ? null : ttl),
                approvers);
        approvers.forEach(id -> signals.signal(UserSignals.HELD, id));
        signals.signal(UserSignals.HELD, row.requesterId());
    }

    /** A request was approved or rejected. */
    void decided(HeldRow row) {
        boolean approved = row.state() == HeldState.APPROVED;
        inbox.resolve(
                SOURCE,
                approverKey(row.id()),
                title((approved ? "Approved by " : "Rejected by ") + row.approverUsername() + ": ", row.summary()));
        String body = approved
                ? "Approved by " + row.approverUsername() + "."
                : "Rejected by " + row.approverUsername() + ": “" + row.decisionReason() + "”";
        toRequester(row, approved ? Severity.SUCCESS : Severity.DANGER, approved ? "Approved: " : "Rejected: ", body);
    }

    /** A request ended without running, or after running: cancelled, expired, refused, succeeded, failed or unknown. */
    void ended(HeldRow row) {
        inbox.resolve(SOURCE, approverKey(row.id()), title(endTitle(row.state()), row.summary()));
        Severity severity =
                switch (row.state()) {
                    case SUCCEEDED -> Severity.SUCCESS;
                    case CANCELLED, EXPIRED -> Severity.WARNING;
                    default -> Severity.DANGER;
                };
        toRequester(row, severity, endTitle(row.state()), row.outcomeDetail());
    }

    /** Several requests ended together, as by a job or a provider's removal. */
    void ended(List<HeldRow> rows) {
        rows.forEach(this::ended);
    }

    private void toRequester(HeldRow row, Severity severity, String prefix, String body) {
        inbox.post(
                SOURCE,
                new Notice(
                        "approval."
                                + row.state()
                                        .name()
                                        .toLowerCase(java.util.Locale.ROOT)
                                        .replace('_', '-'),
                        severity,
                        title(prefix, row.summary()),
                        body == null ? null : truncate(body, Notice.MAX_BODY),
                        row.link(),
                        "held-outcome:" + row.id(),
                        Map.of(
                                "heldOperation",
                                row.id().toString(),
                                "state",
                                row.state().name()),
                        null),
                List.of(row.requesterId()));
        signals.signal(UserSignals.HELD, row.requesterId());
    }

    private static String endTitle(HeldState state) {
        return switch (state) {
            case CANCELLED -> "Cancelled: ";
            case EXPIRED -> "Expired: ";
            case REFUSED -> "Not run: ";
            case SUCCEEDED -> "Done: ";
            case FAILED -> "Failed: ";
            case OUTCOME_UNKNOWN -> "Outcome unknown: ";
            default -> state.name() + ": ";
        };
    }

    /** The dedupe key of the approvers' items for a request. */
    static String approverKey(UUID heldId) {
        return "held:" + heldId;
    }

    private static String title(String prefix, String summary) {
        return truncate(prefix + summary, Notice.MAX_TITLE);
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
