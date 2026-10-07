package io.github.sudoitir.artemisstudio.kernel.approval;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.sudoitir.artemisstudio.kernel.gate.GateLease;
import io.github.sudoitir.artemisstudio.kernel.gate.GateLeases;
import io.github.sudoitir.artemisstudio.kernel.gate.GateTicket;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * The tickets the gate issued (ADR-0180). A ticket is a record, so one built anywhere else can equal an issued one;
 * this registry compares by identity (Caffeine's weak keys), so only the very object the gate handed out counts. A
 * covering ticket is honoured while the gate's action runs and while work that continues the operation holds a
 * {@link GateLease} on it, and is revoked when the last of them ends. A replay ticket is used once.
 */
@Component
class IssuedTickets implements GateLeases {

    private final Cache<GateTicket, Issued> issued =
            Caffeine.newBuilder().weakKeys().build();

    /** A replay ticket's single use, or a covering ticket's holds: the gate's action and each lease. */
    private record Issued(AtomicBoolean used, AtomicInteger holds) {}

    /** A ticket that covers the items of an operation the gate let through; {@link #release} it when the action ends. */
    GateTicket covering(String type) {
        GateTicket ticket = new GateTicket(null, type, null);
        issued.put(ticket, new Issued(new AtomicBoolean(false), new AtomicInteger(1)));
        return ticket;
    }

    /** A ticket that lets one approved held operation through the gate once. */
    GateTicket replay(UUID heldId, String type, String paramsHash) {
        GateTicket ticket = new GateTicket(heldId, type, paramsHash);
        issued.put(ticket, new Issued(new AtomicBoolean(false), new AtomicInteger(1)));
        return ticket;
    }

    /** Whether the gate issued this very ticket and still honours it. */
    boolean issued(GateTicket ticket) {
        return ticket != null && issued.getIfPresent(ticket) != null;
    }

    @Override
    public Optional<GateLease> retain(GateTicket ticket) {
        Issued entry = ticket == null || ticket.replay() ? null : issued.getIfPresent(ticket);
        if (entry == null) {
            return Optional.empty();
        }
        for (int holds = entry.holds().get(); holds > 0; holds = entry.holds().get()) {
            if (entry.holds().compareAndSet(holds, holds + 1)) {
                return Optional.of(new GateLease(ticket, () -> release(ticket)));
            }
        }
        return Optional.empty();
    }

    /** Ends one hold on a covering ticket; the last revokes it. */
    void release(GateTicket ticket) {
        Issued entry = issued.getIfPresent(ticket);
        if (entry != null && entry.holds().decrementAndGet() == 0) {
            issued.invalidate(ticket);
        }
    }

    /** Uses an issued replay ticket; true only the first time. */
    boolean consume(GateTicket ticket) {
        Issued entry = ticket == null ? null : issued.getIfPresent(ticket);
        return entry != null && entry.used().compareAndSet(false, true);
    }

    /** Whether an issued replay ticket was used. */
    boolean consumed(GateTicket ticket) {
        Issued entry = issued.getIfPresent(ticket);
        return entry != null && entry.used().get();
    }
}
