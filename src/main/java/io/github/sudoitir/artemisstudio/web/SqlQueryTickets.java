package io.github.sudoitir.artemisstudio.web;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Short-lived references to a query, so that executing one never puts its text in a
 * URL (ADR-0064).
 *
 * <p>An {@code EventSource} can only issue a GET, so the console used to send the
 * operator's SQL as a query parameter — which writes their predicates, and therefore
 * the values they are searching for, into every proxy access log on the path, and
 * hits a URL length limit on a long query. A ticket is opaque, single-use, and
 * expires in a minute.
 *
 * <p>In memory on purpose. A ticket is worthless a minute after it is issued and
 * meaningless to another instance, so persisting it would add a table and a reaper to
 * store something whose whole value is that it is transient.
 */
@Component
public class SqlQueryTickets {

    /** Long enough for the browser to open the stream, short enough to be worthless if leaked. */
    private static final Duration TTL = Duration.ofMinutes(1);

    private final Map<UUID, Ticket> tickets = new ConcurrentHashMap<>();

    /**
     * @param owner the authenticated name that issued it. The stream re-checks cluster
     *     permission on its own, and this stops a leaked id being redeemed by someone
     *     else who happens to have access to the same cluster.
     */
    public record Ticket(UUID clusterId, String sql, boolean tail, String owner, Instant expiresAt) {}

    public UUID issue(UUID clusterId, String sql, boolean tail, String owner) {
        expire();
        UUID id = UUID.randomUUID();
        tickets.put(id, new Ticket(clusterId, sql, tail, owner, Instant.now().plus(TTL)));
        return id;
    }

    /** Redeem a ticket. Single use: a second attempt with the same id finds nothing. */
    public Optional<Ticket> redeem(UUID id, UUID clusterId, String owner) {
        expire();
        Ticket ticket = tickets.remove(id);
        if (ticket == null || !ticket.clusterId().equals(clusterId)) {
            return Optional.empty();
        }
        return java.util.Objects.equals(ticket.owner(), owner) ? Optional.of(ticket) : Optional.empty();
    }

    /** The authenticated name to bind a ticket to, or {@code "anonymous"} when there is none. */
    public static String currentOwner() {
        org.springframework.security.core.Authentication authentication =
                org.springframework.security.core.context.SecurityContextHolder.getContext()
                        .getAuthentication();
        return authentication == null ? "anonymous" : authentication.getName();
    }

    private void expire() {
        Instant now = Instant.now();
        tickets.values().removeIf(ticket -> ticket.expiresAt().isBefore(now));
    }
}
