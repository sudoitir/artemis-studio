package io.github.sudoitir.artemisstudio.kernel.approval;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.sudoitir.artemisstudio.kernel.gate.GateScope;
import io.github.sudoitir.artemisstudio.kernel.gate.GateTicket;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/**
 * The tickets the gate issued (ADR-0180). A ticket is a record, so one built anywhere else can equal an issued one;
 * this registry compares by identity (Caffeine's weak keys), so only the very object the gate handed out counts. A
 * ticket lives as long as some work still holds it, such as a bulk run's workers carrying {@link GateScope#COVERED},
 * and is forgotten once nothing does. A replay ticket is used once.
 */
@Component
class IssuedTickets {

    private final Cache<GateTicket, AtomicBoolean> issued =
            Caffeine.newBuilder().weakKeys().build();

    /** A ticket that covers the items of an operation the gate let through. */
    GateTicket covering(String type) {
        GateTicket ticket = new GateTicket(null, type, null);
        issued.put(ticket, new AtomicBoolean(false));
        return ticket;
    }

    /** A ticket that lets one approved held operation through the gate once. */
    GateTicket replay(UUID heldId, String type, String paramsHash) {
        GateTicket ticket = new GateTicket(heldId, type, paramsHash);
        issued.put(ticket, new AtomicBoolean(false));
        return ticket;
    }

    /** Whether the gate issued this very ticket. */
    boolean issued(GateTicket ticket) {
        return ticket != null && issued.getIfPresent(ticket) != null;
    }

    /** Uses an issued replay ticket; true only the first time. */
    boolean consume(GateTicket ticket) {
        AtomicBoolean used = ticket == null ? null : issued.getIfPresent(ticket);
        return used != null && used.compareAndSet(false, true);
    }

    /** Whether an issued replay ticket was used. */
    boolean consumed(GateTicket ticket) {
        AtomicBoolean used = issued.getIfPresent(ticket);
        return used != null && used.get();
    }
}
