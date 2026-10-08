package io.github.sudoitir.artemisstudio.kernel.approval;

import io.github.sudoitir.artemisstudio.kernel.audit.AuditEvent;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditScope;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditService;
import io.github.sudoitir.artemisstudio.kernel.core.ConflictException;
import io.github.sudoitir.artemisstudio.kernel.core.NotFoundException;
import io.github.sudoitir.artemisstudio.kernel.gate.ApprovalProviderRegistry;
import io.github.sudoitir.artemisstudio.kernel.gate.ApprovalProviderRegistry.AttachedProvider;
import io.github.sudoitir.artemisstudio.kernel.gate.ApprovalUnavailableException;
import io.github.sudoitir.artemisstudio.kernel.gate.Approver;
import io.github.sudoitir.artemisstudio.kernel.gate.AuthKind;
import io.github.sudoitir.artemisstudio.kernel.gate.ExecutionMode;
import io.github.sudoitir.artemisstudio.kernel.gate.GateContext;
import io.github.sudoitir.artemisstudio.kernel.gate.HeldEvent;
import io.github.sudoitir.artemisstudio.kernel.gate.HeldState;
import io.github.sudoitir.artemisstudio.kernel.gate.Vote;
import io.github.sudoitir.artemisstudio.kernel.gate.VoteCheck;
import io.github.sudoitir.artemisstudio.kernel.security.ActorResolver;
import io.github.sudoitir.artemisstudio.kernel.security.ReauthenticationRequiredException;
import io.github.sudoitir.artemisstudio.kernel.security.SessionAuthentication;
import io.github.sudoitir.artemisstudio.kernel.security.SessionFacts;
import io.github.sudoitir.artemisstudio.kernel.security.StudioPrincipal;
import io.github.sudoitir.artemisstudio.kernel.security.TokenPrincipal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deciding and cancelling held requests (ADR-0181, design decision 6). A decision comes only from a person in a
 * browser session whose sign-in is fresh, who echoes the hash and version of the request they were shown, and who
 * passes Studio's rules and then the provider's. One conditional update records it, so two approvers racing get one
 * success and one 409. Every refused decision is audited and written to the request's timeline.
 */
@Component
@Slf4j
class Decisions {

    private static final String SESSION_REQUIRED = "session-required";
    private static final String HELD_OPERATION_PARAM = "heldOperation";
    private static final String HELD_OPERATION_TARGET = "HELD_OPERATION";

    private final HeldStore store;
    private final Seals seals;
    private final ApproverRules rules;
    private final ApprovalProviderRegistry providers;
    private final ProviderCalls calls;
    private final ApprovalNotices notices;
    private final AuditService audit;
    private final ActorResolver actors;
    private final SessionAuthentication sessions;
    private final GateBounds bounds;
    private final Executions executions;
    private final TransactionTemplate tx;

    Decisions(
            HeldStore store,
            Seals seals,
            ApproverRules rules,
            ApprovalProviderRegistry providers,
            ProviderCalls calls,
            ApprovalNotices notices,
            AuditService audit,
            ActorResolver actors,
            SessionAuthentication sessions,
            GateBounds bounds,
            Executions executions,
            PlatformTransactionManager transactions) {
        this.store = store;
        this.seals = seals;
        this.rules = rules;
        this.providers = providers;
        this.calls = calls;
        this.notices = notices;
        this.audit = audit;
        this.actors = actors;
        this.sessions = sessions;
        this.bounds = bounds;
        this.executions = executions;
        this.tx = new TransactionTemplate(transactions);
    }

    /**
     * Records the current user's vote on a held request.
     *
     * @param paramsHash the hex hash of the request the voter was shown
     * @param version the version of the request the voter was shown
     * @throws VoteRefusedException when a rule refuses the vote, after auditing it
     * @throws ReauthenticationRequiredException when the voter's sign-in is not fresh enough
     * @throws ConflictException when the request was already decided, cancelled or expired, or another approver won
     */
    HeldRow decide(UUID id, Vote vote, String reason, String paramsHash, int version) {
        StudioPrincipal me = principal();
        HeldRow row = store.get(id).orElseThrow(() -> new NotFoundException("Held operation", id));
        String why = reason == null || reason.isBlank() ? null : reason.strip();
        SessionFacts session = requireFreshSession(row, me, vote);
        requireShownRequest(row, me, vote, paramsHash, version, why);
        AttachedProvider provider = providers
                .armedProviderId()
                .filter(row.providerId()::equals)
                .flatMap(providers::attached)
                .orElseThrow(() -> new ApprovalUnavailableException(
                        "The approval provider that held this request is not running here, so it cannot be decided"
                                + " now."));
        requireVotePermitted(row, me, vote, session, provider);
        HeldState to = vote == Vote.APPROVE ? HeldState.APPROVED : HeldState.REJECTED;
        Instant decidedAt = store.now();
        byte[] sealed = seals.sealDecision(
                row.id(), row.paramsHashHex(), new Seals.Decision(me.userId(), vote, decidedAt.toString()));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(HELD_OPERATION_PARAM, row.id().toString());
        params.put("type", row.type());
        if (why != null) {
            params.put("reason", why);
        }
        AuditEvent event = ScopedValue.where(AuditScope.PARENT, row.requestAuditId())
                .call(() -> audit.begin(
                        actors.resolve(),
                        vote == Vote.APPROVE ? "OPERATION_APPROVED" : "OPERATION_REJECTED",
                        HELD_OPERATION_TARGET,
                        row.summary(),
                        row.clusterId(),
                        null,
                        params,
                        false));
        Optional<HeldRow> decided;
        try {
            decided = tx.execute(status -> {
                Optional<HeldRow> updated = store.decide(
                        row.id(),
                        version,
                        new HeldStore.Verdict(to, me.userId(), me.getUsername(), decidedAt, why),
                        sealed,
                        bounds.runWindow());
                updated.ifPresent(d -> {
                    store.event(d.id(), Executions.kindOf(to), me.userId(), me.getUsername(), why);
                    notices.decided(d);
                });
                return updated;
            });
        } catch (RuntimeException e) {
            audit.fail(event, e.getMessage());
            throw e;
        }
        if (decided.isEmpty()) {
            audit.refuse(event, "Another decision, a cancellation or the expiry came first.");
            throw new ConflictException(
                    "held-operation-closed",
                    "Someone else decided this request first, or it was" + " cancelled or expired.");
        }
        audit.succeed(event, 1);
        log.info("approval-gate decided id={} vote={} approver={}", row.id(), vote, me.getUsername());
        if (to == HeldState.APPROVED && row.mode() == ExecutionMode.ON_APPROVAL) {
            executions.submit(row.id());
        }
        return decided.get();
    }

    /** Only a person in a browser session decides, and only while their sign-in is fresh. */
    private SessionFacts requireFreshSession(HeldRow row, StudioPrincipal me, Vote vote) {
        if (me instanceof TokenPrincipal) {
            throw refuse(
                    row,
                    me,
                    vote,
                    HttpStatus.FORBIDDEN,
                    SESSION_REQUIRED,
                    "Only a person signed in to the console may decide a request; an API token cannot.");
        }
        if (GateContext.ORIGIN.isBound() && GateContext.ORIGIN.get() == AuthKind.AGENT) {
            throw refuse(
                    row, me, vote, HttpStatus.FORBIDDEN, SESSION_REQUIRED, "An assistant may not decide a request.");
        }
        SessionFacts facts = sessions.current()
                .orElseThrow(() -> refuse(
                        row,
                        me,
                        vote,
                        HttpStatus.FORBIDDEN,
                        SESSION_REQUIRED,
                        "Only a person signed in to the console may decide a request."));
        Instant authenticatedAt = facts.authenticatedAt();
        if (authenticatedAt == null
                || authenticatedAt.isBefore(Instant.now().minus(SessionAuthentication.REAUTHENTICATION_WINDOW))) {
            record(row, me, vote, "Sign-in not fresh; confirmation asked.");
            throw new ReauthenticationRequiredException();
        }
        return facts;
    }

    /** The request is still open, is the one the voter was shown, and the vote carries the reason it needs. */
    private void requireShownRequest(
            HeldRow row, StudioPrincipal me, Vote vote, String paramsHash, int version, String why) {
        if (row.state() != HeldState.HELD || !row.expiresAt().isAfter(store.now())) {
            throw new ConflictException("held-operation-closed", closedMessage(row));
        }
        if (paramsHash == null || !paramsHash.equalsIgnoreCase(row.paramsHashHex()) || version != row.version()) {
            throw refuse(
                    row,
                    me,
                    vote,
                    HttpStatus.CONFLICT,
                    "held-operation-changed",
                    "This request is not the one you were shown. Reload it and decide again.");
        }
        if (why != null && why.length() > GateEngine.MAX_REASON) {
            throw new IllegalArgumentException("A reason is at most " + GateEngine.MAX_REASON + " characters.");
        }
        if (vote == Vote.REJECT && why == null) {
            throw refuse(
                    row,
                    me,
                    vote,
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "decision-reason-required",
                    "Say why you reject it; the requester sees your reason.");
        }
    }

    /** Studio's rules say whether this person may vote, then the provider's check does. */
    private void requireVotePermitted(
            HeldRow row, StudioPrincipal me, Vote vote, SessionFacts session, AttachedProvider provider) {
        ApproverRules.Subject subject =
                new ApproverRules.Subject(row.requesterId(), row.requestedAt(), row.clusterId());
        Optional<String> refusal =
                rules.refusal(me.userId(), me.getUsername(), subject, provider.approverPermission(), vote);
        if (refusal.isPresent()) {
            throw refuse(row, me, vote, HttpStatus.FORBIDDEN, "vote-refused", refusal.get());
        }
        Approver approver = new Approver(
                me.userId(),
                me.getUsername(),
                session.authenticatedAt(),
                session.mfaVerifiedAt(),
                session.mfaMethod() == null ? null : session.mfaMethod().name().toLowerCase(Locale.ROOT));
        VoteCheck check = calls.call("checkVote", () -> provider.provider().checkVote(row.view(), approver, vote));
        if (!check.allowed()) {
            throw refuse(
                    row,
                    me,
                    vote,
                    HttpStatus.FORBIDDEN,
                    "vote-refused",
                    check.reason() == null
                            ? "The approval policy does not let you decide this request."
                            : check.reason());
        }
    }

    /** The requester withdraws a request that is still held or approved. */
    HeldRow cancel(UUID id) {
        StudioPrincipal me = principal();
        HeldRow row = store.get(id).orElseThrow(() -> new NotFoundException("Held operation", id));
        if (!row.requesterId().equals(me.userId())) {
            throw new AccessDeniedException("Only the requester may cancel a request.");
        }
        AuditEvent event = ScopedValue.where(AuditScope.PARENT, row.requestAuditId())
                .call(() -> audit.begin(
                        actors.resolve(),
                        "OPERATION_CANCELLED",
                        HELD_OPERATION_TARGET,
                        row.summary(),
                        row.clusterId(),
                        null,
                        Map.of(HELD_OPERATION_PARAM, row.id().toString(), "type", row.type()),
                        false));
        Optional<HeldRow> cancelled = tx.execute(status -> {
            Optional<HeldRow> ended = store.end(
                    id,
                    List.of(HeldState.HELD, HeldState.APPROVED),
                    HeldState.CANCELLED,
                    "Cancelled by the requester.");
            ended.ifPresent(e -> {
                store.event(e.id(), HeldEvent.Kind.CANCELLED, me.userId(), me.getUsername(), null);
                notices.ended(e);
            });
            return ended;
        });
        if (cancelled.isEmpty()) {
            audit.refuse(event, "It was no longer held or approved.");
            throw new ConflictException(
                    "held-operation-closed", closedMessage(store.get(id).orElse(row)));
        }
        audit.succeed(event, 1);
        return cancelled.get();
    }

    private static String closedMessage(HeldRow row) {
        return switch (row.state()) {
            case HELD -> "This request has expired.";
            case APPROVED, EXECUTING -> "This request was already approved.";
            default ->
                "This request already ended: "
                        + row.state().name().toLowerCase(Locale.ROOT).replace('_', ' ') + ".";
        };
    }

    /** Audits and records a refused vote, and returns what to throw. */
    private VoteRefusedException refuse(
            HeldRow row, StudioPrincipal me, Vote vote, HttpStatus status, String slug, String message) {
        record(row, me, vote, message);
        return new VoteRefusedException(status, slug, message);
    }

    private void record(HeldRow row, StudioPrincipal me, Vote vote, String message) {
        ScopedValue.where(AuditScope.PARENT, row.requestAuditId())
                .run(() -> audit.refused(
                        actors.resolve(),
                        vote == Vote.REJECT ? "OPERATION_REJECTED" : "OPERATION_APPROVED",
                        HELD_OPERATION_TARGET,
                        row.summary(),
                        row.clusterId(),
                        Map.of(HELD_OPERATION_PARAM, row.id().toString(), "type", row.type()),
                        message));
        tx.executeWithoutResult(
                status -> store.event(row.id(), HeldEvent.Kind.VOTE_REFUSED, me.userId(), me.getUsername(), message));
        log.info("approval-gate vote-refused id={} voter={} reason=\"{}\"", row.id(), me.getUsername(), message);
    }

    private static StudioPrincipal principal() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof StudioPrincipal principal) || principal.userId() == null) {
            throw new AccessDeniedException("Sign in to decide or cancel a request.");
        }
        return principal;
    }
}
