package io.github.sudoitir.artemisstudio.kernel.approval;

import io.github.sudoitir.artemisstudio.kernel.gate.EventCursor;
import io.github.sudoitir.artemisstudio.kernel.gate.HeldEvent;
import io.github.sudoitir.artemisstudio.kernel.gate.HeldFilter;
import io.github.sudoitir.artemisstudio.kernel.gate.HeldOperationView;
import io.github.sudoitir.artemisstudio.kernel.gate.HeldState;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginScopedBeans;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Gives each plugin its {@link ScopedHeldOperations} (ADR-0111, ADR-0180): read access to the requests it held, a
 * gap-free event feed, and the right to expire one of its own.
 */
@Component
class HeldOperationsService implements PluginScopedBeans {

    static final String BEAN_NAME = "heldOperations";

    /** The most events one read returns. */
    static final int MAX_EVENTS = 1000;

    private final HeldStore store;
    private final ApprovalNotices notices;
    private final TransactionTemplate tx;

    HeldOperationsService(HeldStore store, ApprovalNotices notices, PlatformTransactionManager transactions) {
        this.store = store;
        this.notices = notices;
        this.tx = new TransactionTemplate(transactions);
    }

    @Override
    public Map<String, Object> beansFor(String pluginId) {
        return Map.of(BEAN_NAME, new ScopedHeldOperations(this, pluginId));
    }

    Optional<HeldOperationView> get(String providerId, UUID id) {
        return store.get(id).filter(row -> row.providerId().equals(providerId)).map(HeldRow::view);
    }

    List<HeldOperationView> list(String providerId, HeldFilter filter) {
        Objects.requireNonNull(filter, "filter");
        return store
                .list(filter.states(), filter.requesterId(), providerId, filter.clusterId(), null, filter.limit())
                .stream()
                .map(HeldRow::view)
                .toList();
    }

    List<HeldEvent> eventsAfter(String providerId, EventCursor cursor, int limit) {
        if (limit < 1 || limit > MAX_EVENTS) {
            throw new IllegalArgumentException("limit must be from 1 to " + MAX_EVENTS);
        }
        return store.eventsAfter(providerId, cursor == null ? EventCursor.START : cursor, limit);
    }

    boolean expire(String providerId, UUID id, String reason) {
        if (reason == null || reason.isBlank() || reason.length() > GateEngine.MAX_REASON) {
            throw new IllegalArgumentException("Expiring a request needs a reason of 1 to " + GateEngine.MAX_REASON
                    + " characters; the requester sees it.");
        }
        return Boolean.TRUE.equals(tx.execute(status -> store.get(id)
                .filter(row -> row.providerId().equals(providerId))
                .flatMap(row -> store.end(id, List.of(HeldState.HELD, HeldState.APPROVED), HeldState.EXPIRED, reason))
                .map(ended -> {
                    store.event(ended.id(), HeldEvent.Kind.EXPIRED, null, null, reason);
                    notices.ended(ended);
                    return true;
                })
                .orElse(false)));
    }
}
