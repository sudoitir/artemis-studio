package io.github.sudoitir.artemisstudio.feature.sql.web;

import io.github.sudoitir.artemisstudio.kernel.security.ActorResolver;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * <p>Kept in an unlogged table, so that the request that issues a ticket and the stream that
 * redeems it may reach different replicas (ADR-0152). Redeeming is one {@code DELETE ... RETURNING},
 * so a ticket is used once however many replicas race for it. The rows that expire unredeemed are
 * purged by the data lifecycle ({@link SqlQueryTicketStore}).
 */
@Component
@RequiredArgsConstructor
public class SqlQueryTickets {

    /** What a ticket stands for. */
    public record Ticket(String sql, boolean tail) {}

    private final ActorResolver actors;
    private final JdbcTemplate jdbc;

    /** Valid for a minute: long enough for the browser to open the stream, short enough to be worthless if leaked. */
    public UUID issue(UUID clusterId, String sql, boolean tail, String owner) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO sql_query_ticket (expires_at, sql, owner, id, cluster_id, tail)"
                        + " VALUES (now() + interval '1 minute', ?, ?, ?, ?, ?)",
                sql,
                owner,
                id,
                clusterId,
                tail);
        return id;
    }

    /**
     * Redeem a ticket. Single use: a second attempt with the same id finds nothing, and neither does
     * one for another cluster, by another owner, or after the ticket expired.
     */
    public Optional<Ticket> redeem(UUID id, UUID clusterId, String owner) {
        return jdbc
                .query(
                        "DELETE FROM sql_query_ticket WHERE id = ? AND cluster_id = ? AND owner = ?"
                                + " AND expires_at > now() RETURNING sql, tail",
                        (rs, row) -> new Ticket(rs.getString("sql"), rs.getBoolean("tail")),
                        id,
                        clusterId,
                        owner)
                .stream()
                .findFirst();
    }

    /** The caller to bind a ticket to: the same name when it is issued and when it is redeemed. */
    public String currentOwner() {
        return actors.resolve().username();
    }
}
