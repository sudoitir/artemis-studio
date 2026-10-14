package io.github.sudoitir.rahdar.feature.alerting;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.rahdar.support.PostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** AUDIT_DELIVERY is active for a receiver with a gap or one whose oldest unacknowledged record is old, and for no other. */
class AuditDeliverySignalTest extends PostgresIntegrationTest {

    @Autowired
    AuditDeliverySignal signal;

    @Autowired
    JdbcTemplate jdbc;

    private final String receiver =
            "signal-test-" + UUID.randomUUID().toString().substring(0, 8);
    private final String action = "SIGNAL_TEST_" + UUID.randomUUID().toString().substring(0, 8);

    @BeforeEach
    void receiver() {
        jdbc.update("INSERT INTO audit_receiver (plugin_id) VALUES (?)", receiver);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM audit_receiver WHERE plugin_id = ?", receiver);
        jdbc.update("DELETE FROM audit_event WHERE action = ?", action);
    }

    private void event(String age) {
        jdbc.update("INSERT INTO audit_event (ts, action) VALUES (now() - ?::interval, ?)", age, action);
    }

    private void caughtUp() {
        jdbc.update("""
                UPDATE audit_receiver SET pos_txid = head.txid, pos_seq = head.seq
                FROM (SELECT pos_txid AS txid, pos_seq AS seq FROM audit_event
                      ORDER BY pos_txid DESC, pos_seq DESC LIMIT 1) head
                WHERE plugin_id = ?""", receiver);
    }

    @Test
    void theSubjectIsKnownWhateverItsState() {
        event("1 minute");
        caughtUp();

        var evaluation = signal.evaluate();

        assertThat(evaluation.universe()).contains(receiver);
        assertThat(evaluation.active()).doesNotContainKey(receiver);
    }

    @Test
    void aReceiverBehindByLessThanTheAlertAgeIsNotActive() {
        caughtUp();
        event("1 minute");

        assertThat(signal.evaluate().active()).doesNotContainKey(receiver);
    }

    @Test
    void aReceiverWhoseOldestUnacknowledgedRecordIsOlderThanTheAlertAgeIsActiveWithItsAge() {
        caughtUp();
        event("3 hours");

        assertThat(signal.evaluate().active().get(receiver)).isBetween(3 * 3600.0 - 60, 3 * 3600.0 + 60);
    }

    @Test
    void anUndeliveredGapIsActiveEvenWhenTheReceiverIsCaughtUp() {
        event("1 minute");
        caughtUp();
        jdbc.update(
                "INSERT INTO audit_receiver_gap (count, first_ts, last_ts, plugin_id) VALUES (3, now(), now(), ?)",
                receiver);

        assertThat(signal.evaluate().active()).containsKey(receiver);

        jdbc.update("UPDATE audit_receiver_gap SET delivered_at = now() WHERE plugin_id = ?", receiver);

        assertThat(signal.evaluate().active()).doesNotContainKey(receiver);
    }
}
