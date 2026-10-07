package io.github.sudoitir.artemisstudio.kernel.approval;

import io.github.sudoitir.artemisstudio.kernel.core.NotFoundException;
import io.github.sudoitir.artemisstudio.kernel.gate.ApprovalProviderRegistry;
import io.github.sudoitir.artemisstudio.kernel.gate.ApprovalProviderRegistry.AttachedProvider;
import io.github.sudoitir.artemisstudio.kernel.gate.HeldEvent;
import io.github.sudoitir.artemisstudio.kernel.gate.HeldOperationView;
import io.github.sudoitir.artemisstudio.kernel.gate.HeldState;
import io.github.sudoitir.artemisstudio.kernel.gate.Vote;
import io.github.sudoitir.artemisstudio.kernel.security.PermissionResolver;
import io.github.sudoitir.artemisstudio.kernel.security.Permissions;
import io.github.sudoitir.artemisstudio.kernel.security.StudioPrincipal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * What the console asks about held operations, for the current user (ADR-0180): their own requests, the ones they may
 * decide, one request in full, a decision, a cancellation, and whether the gate is armed. A request is visible to its
 * requester, to whoever may decide it or decided it, and to those who read the audit of its scope (the cluster's
 * readers, or user administrators for an installation-wide one); to anyone else it does not exist.
 */
@Component
public class Approvals {

    /** How many held requests a "decidable by me" listing looks through. */
    static final int DECIDABLE_SCAN = 500;

    private final HeldStore store;
    private final ApproverRules rules;
    private final ApprovalProviderRegistry providers;
    private final PermissionResolver permissions;
    private final Decisions decisions;
    private final Executions executions;
    private final BreakGlass breakGlass;

    Approvals(
            HeldStore store,
            ApproverRules rules,
            ApprovalProviderRegistry providers,
            PermissionResolver permissions,
            Decisions decisions,
            Executions executions,
            BreakGlass breakGlass) {
        this.store = store;
        this.rules = rules;
        this.providers = providers;
        this.permissions = permissions;
        this.decisions = decisions;
        this.executions = executions;
        this.breakGlass = breakGlass;
    }

    /** Which requests a listing shows. */
    public enum Scope {
        /** The current user's own requests. */
        MINE,
        /** Held requests the current user may decide now. */
        DECIDABLE
    }

    /**
     * One request as its page shows it.
     *
     * @param version what a decision must echo, with {@code view.paramsHash()}
     * @param link the request's page in the console
     * @param decideRefusal why the current user may not decide it, or null when they may
     * @param requesterLacksPermission whether its requester could no longer run it; null when unknown or ended
     */
    public record Detail(
            HeldOperationView view,
            int version,
            String approverHint,
            String link,
            List<HeldEvent> events,
            boolean mine,
            boolean canDecide,
            String decideRefusal,
            boolean canCancel,
            Boolean requesterLacksPermission) {}

    /** A page of requests, newest first, and the id to pass as {@code before} for the next; null at the end. */
    public record Page(List<HeldOperationView> items, UUID next) {}

    /**
     * Whether the gate is armed and by whom, whether its provider runs on this instance, and whether break-glass is
     * on, for the console's banner.
     */
    public record Status(boolean armed, String providerId, boolean attached, boolean breakGlass) {}

    public Page list(Scope scope, Collection<HeldState> states, UUID before, int limit) {
        StudioPrincipal me = principal();
        List<HeldRow> rows;
        if (scope == Scope.MINE) {
            rows = store.list(states, me.userId(), null, null, before, limit + 1);
        } else {
            Optional<AttachedProvider> provider = providers.armedProviderId().flatMap(providers::attached);
            if (provider.isEmpty()) {
                return new Page(List.of(), null);
            }
            rows = new ArrayList<>();
            for (HeldRow row :
                    store.list(Set.of(HeldState.HELD), null, provider.get().pluginId(), null, before, DECIDABLE_SCAN)) {
                if (refusal(me, row, provider.get()).isEmpty()) {
                    rows.add(row);
                    if (rows.size() > limit) {
                        break;
                    }
                }
            }
        }
        boolean more = rows.size() > limit;
        List<HeldRow> page = more ? rows.subList(0, limit) : rows;
        return new Page(
                page.stream().map(HeldRow::view).toList(), more ? page.getLast().id() : null);
    }

    public Detail get(UUID id) {
        StudioPrincipal me = principal();
        HeldRow row = visible(me, id);
        return detail(me, row);
    }

    /**
     * Records the current user's vote. Unlike reading, deciding does not first ask whether the request is visible: a
     * vote from someone who may not decide it is a refused decision, and every refused decision is audited.
     */
    public Detail decide(UUID id, Vote vote, String reason, String paramsHash, int version) {
        StudioPrincipal me = principal();
        return detail(me, decisions.decide(id, vote, reason, paramsHash, version));
    }

    public Detail cancel(UUID id) {
        StudioPrincipal me = principal();
        visible(me, id);
        return detail(me, decisions.cancel(id));
    }

    public Status status() {
        Optional<String> armed = providers.armedProviderId();
        return new Status(
                armed.isPresent(),
                armed.orElse(null),
                armed.flatMap(providers::attached).isPresent(),
                breakGlass.active());
    }

    private Detail detail(StudioPrincipal me, HeldRow row) {
        Optional<AttachedProvider> provider =
                providers.armedProviderId().filter(row.providerId()::equals).flatMap(providers::attached);
        boolean mine = row.requesterId().equals(me.userId());
        String refusal;
        if (row.state() != HeldState.HELD) {
            refusal = "It is no longer waiting for a decision.";
        } else if (provider.isEmpty()) {
            refusal = "The approval provider is not running here.";
        } else {
            refusal = refusal(me, row, provider.get()).orElse(null);
        }
        return new Detail(
                row.view(),
                row.version(),
                row.approverHint(),
                row.link(),
                store.timeline(row.id()),
                mine,
                refusal == null,
                refusal,
                mine && (row.state() == HeldState.HELD || row.state() == HeldState.APPROVED),
                executions.requesterLacksPermission(row).orElse(null));
    }

    private Optional<String> refusal(StudioPrincipal me, HeldRow row, AttachedProvider provider) {
        return rules.refusal(
                me.userId(),
                me.getUsername(),
                new ApproverRules.Subject(row.requesterId(), row.requestedAt(), row.clusterId()),
                provider.approverPermission(),
                null);
    }

    private HeldRow visible(StudioPrincipal me, UUID id) {
        HeldRow row = store.get(id).orElseThrow(() -> new NotFoundException("Held operation", id));
        if (row.requesterId().equals(me.userId()) || me.userId().equals(row.approverId())) {
            return row;
        }
        boolean auditReader = row.clusterId() == null
                ? permissions.can(me, null, Permissions.USER_ADMIN)
                : permissions.can(me, row.clusterId(), Permissions.CLUSTER_READ);
        if (auditReader) {
            return row;
        }
        Optional<AttachedProvider> provider = providers.attached(row.providerId());
        if (provider.isPresent() && refusal(me, row, provider.get()).isEmpty()) {
            return row;
        }
        throw new NotFoundException("Held operation", id);
    }

    private static StudioPrincipal principal() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof StudioPrincipal principal) || principal.userId() == null) {
            throw new AccessDeniedException("Sign in to see held requests.");
        }
        return principal;
    }
}
