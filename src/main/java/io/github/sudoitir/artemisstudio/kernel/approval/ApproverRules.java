package io.github.sudoitir.artemisstudio.kernel.approval;

import io.github.sudoitir.artemisstudio.kernel.gate.Vote;
import io.github.sudoitir.artemisstudio.kernel.security.AccessChangeLog;
import io.github.sudoitir.artemisstudio.kernel.security.PermissionHolders;
import io.github.sudoitir.artemisstudio.kernel.security.PermissionResolver;
import io.github.sudoitir.artemisstudio.kernel.security.StudioPrincipal;
import io.github.sudoitir.artemisstudio.kernel.security.UserIdentityFacts;
import io.github.sudoitir.artemisstudio.kernel.security.UserIdentityFacts.Facts;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Studio's own approver rules, which hold whatever provider is installed (ADR-0181): the approver is another
 * person, holds the approver permission at the operation's scope now, had an account before the request, and the
 * requester has not changed anyone else's access since asking. The provider's {@code checkVote} comes after these.
 */
@Component
class ApproverRules {

    /** The most approvers a hold notifies; {@code Inbox.MAX_RECIPIENTS}. */
    static final int MAX_APPROVERS = 500;

    private final UserIdentityFacts identities;
    private final PermissionResolver permissions;
    private final PermissionHolders holders;
    private final AccessChangeLog accessChanges;

    ApproverRules(
            UserIdentityFacts identities,
            PermissionResolver permissions,
            PermissionHolders holders,
            AccessChangeLog accessChanges) {
        this.identities = identities;
        this.permissions = permissions;
        this.holders = holders;
        this.accessChanges = accessChanges;
    }

    /** What a decision is about: who asked, when, and where. */
    record Subject(UUID requesterId, Instant requestedAt, UUID clusterId) {}

    /**
     * Why {@code approverId} may not cast {@code vote} on the request, or empty when Studio's rules allow it.
     *
     * @param vote {@code null} to ask whether they may decide at all; the access-change rule holds only for approving
     */
    Optional<String> refusal(
            UUID approverId, String approverUsername, Subject subject, String approverPermission, Vote vote) {
        if (approverId.equals(subject.requesterId())) {
            return Optional.of("You made this request. Another person must decide it.");
        }
        Optional<Facts> approver = identities.of(approverId);
        Optional<Facts> requester = identities.of(subject.requesterId());
        if (approver.isEmpty() || requester.isEmpty() || !approver.get().enabled()) {
            return Optional.of("Your account cannot decide requests.");
        }
        Optional<String> personal = personal(approver.get(), requester.get(), subject.requestedAt());
        if (personal.isPresent()) {
            return personal;
        }
        if (!permissions.can(
                StudioPrincipal.live(approverId, approverUsername, false), subject.clusterId(), approverPermission)) {
            return Optional.of("You do not hold the " + approverPermission + " permission for this request's scope.");
        }
        if (vote == Vote.APPROVE && accessChanges.changedOthersSince(subject.requesterId(), subject.requestedAt())) {
            return Optional.of("The requester changed another user's access after asking, so this request can no"
                    + " longer be approved. Reject it, and they can ask again.");
        }
        return Optional.empty();
    }

    /**
     * Who may approve a request {@code requesterId} makes now at {@code clusterId}: enabled holders of the permission,
     * other than the requester and anyone linked to them, whose accounts already exist.
     */
    List<UUID> eligible(UUID requesterId, UUID clusterId, String approverPermission, Instant requestedAt) {
        Optional<Facts> requester = identities.of(requesterId);
        if (requester.isEmpty()) {
            return Collections.emptyList();
        }
        return holders.holders(clusterId, approverPermission, MAX_APPROVERS + 1).stream()
                .filter(id -> !id.equals(requesterId))
                .filter(id -> identities
                        .of(id)
                        .filter(Facts::enabled)
                        .filter(facts ->
                                personal(facts, requester.get(), requestedAt).isEmpty())
                        .isPresent())
                .limit(MAX_APPROVERS)
                .toList();
    }

    /** The rules about the two people: not linked by email or external identity, and the approver's account older. */
    private static Optional<String> personal(Facts approver, Facts requester, Instant requestedAt) {
        if (approver.email() != null
                && requester.email() != null
                && approver.email()
                        .strip()
                        .toLowerCase(Locale.ROOT)
                        .equals(requester.email().strip().toLowerCase(Locale.ROOT))) {
            return Optional.of("Your account has the requester's email address, so it may not decide their request.");
        }
        if (!Collections.disjoint(approver.identities(), requester.identities())) {
            return Optional.of("Your account is linked to the requester's external identity, so it may not decide"
                    + " their request.");
        }
        if (approver.createdAt() == null || !approver.createdAt().isBefore(requestedAt)) {
            return Optional.of("Your account was created after this request was made, so it may not decide it.");
        }
        return Optional.empty();
    }
}
