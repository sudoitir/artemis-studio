package io.github.sudoitir.artemisstudio.platform.governance;

import io.github.sudoitir.artemisstudio.platform.governance.web.GovernanceRuleViews.PolicyView;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

/**
 * Brings stored content up to the current policy (data-governance spec). Egress already re-governs stored rows,
 * so a new rule protects every read at once; this makes the stored copy itself comply, in bounded batches.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GovernanceRemasking {

    static final int BATCH = 500;
    static final Duration BUDGET = Duration.ofSeconds(10);
    static final long COUNT_CAP = 100_000;

    private final ObjectProvider<StoredContentRemasker> remaskers;
    private final PolicyStore store;

    /** One job run: batches per owner until each is done or the time budget is spent. */
    void run() {
        int version = store.current().version();
        long deadline = System.nanoTime() + BUDGET.toNanos();
        for (StoredContentRemasker remasker : owners()) {
            while (System.nanoTime() < deadline) {
                int rewritten;
                try {
                    rewritten = remasker.remask(version, BATCH);
                } catch (RuntimeException e) {
                    log.warn("Re-masking {} failed; it is retried on the next run", remasker.name(), e);
                    break;
                }
                if (rewritten < BATCH) {
                    break;
                }
            }
        }
    }

    /** How many stored rows are still masked under an earlier version, capped so the count stays cheap. */
    @PreAuthorize(
            "@perm.can(T(io.github.sudoitir.artemisstudio.platform.governance.GovernancePermissions).GOVERNANCE_READ)")
    public PolicyView progress() {
        int version = store.current().version();
        long total = 0;
        boolean capped = false;
        for (StoredContentRemasker remasker : owners()) {
            long below = remasker.countBelow(version, COUNT_CAP);
            total += below;
            capped |= below >= COUNT_CAP;
        }
        return new PolicyView(version, total, capped);
    }

    private List<StoredContentRemasker> owners() {
        return remaskers.orderedStream().toList();
    }
}
