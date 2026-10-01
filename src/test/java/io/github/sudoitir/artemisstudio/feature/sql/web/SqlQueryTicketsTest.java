package io.github.sudoitir.artemisstudio.feature.sql.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.sudoitir.artemisstudio.kernel.security.ActorResolver;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A ticket issued by one replica is redeemed by another, once (ADR-0064, ADR-0152). The store holds
 * no ticket of its own, so two instances on one database stand for two replicas.
 */
class SqlQueryTicketsTest extends PostgresIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    private final UUID cluster = UUID.randomUUID();

    @Test
    void aTicketIssuedOnOneReplicaIsRedeemedOnceOnAnother() {
        SqlQueryTickets a = new SqlQueryTickets(mock(ActorResolver.class), jdbc);
        SqlQueryTickets b = new SqlQueryTickets(mock(ActorResolver.class), jdbc);
        UUID id = a.issue(cluster, "SELECT * FROM \"ORDERS\"", true, "alice");

        assertThat(b.redeem(id, cluster, "alice")).hasValueSatisfying(ticket -> {
            assertThat(ticket.sql()).isEqualTo("SELECT * FROM \"ORDERS\"");
            assertThat(ticket.tail()).isTrue();
        });
        assertThat(a.redeem(id, cluster, "alice")).isEmpty();
        assertThat(b.redeem(id, cluster, "alice")).isEmpty();
    }

    @Test
    void aTicketIsRedeemedOnlyByItsOwnerForItsCluster() {
        SqlQueryTickets tickets = new SqlQueryTickets(mock(ActorResolver.class), jdbc);
        UUID id = tickets.issue(cluster, "SELECT 1", false, "alice");

        assertThat(tickets.redeem(id, cluster, "mallory")).isEmpty();
        assertThat(tickets.redeem(id, UUID.randomUUID(), "alice")).isEmpty();
        assertThat(tickets.redeem(id, cluster, "alice")).isPresent();
    }

    @Test
    void anExpiredTicketIsNotRedeemed() {
        SqlQueryTickets tickets = new SqlQueryTickets(mock(ActorResolver.class), jdbc);
        UUID id = tickets.issue(cluster, "SELECT 1", false, "alice");
        jdbc.update("UPDATE sql_query_ticket SET expires_at = now() - interval '1 second' WHERE id = ?", id);

        assertThat(tickets.redeem(id, cluster, "alice")).isEmpty();
    }
}
