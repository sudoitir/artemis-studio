package io.github.sudoitir.artemisstudio.feature.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.feature.transfer.web.TransferViews.TransferRunView;
import io.github.sudoitir.artemisstudio.platform.broker.StagingQueues;
import jakarta.jms.Connection;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.Test;

/** A move from a queue on one cluster to a queue on another, by ids, by filter and whole (5.2), frozen at t0 (5.9). */
class TransferMoveTest extends TransferTestSupport {

    @Test
    void aMoveByIdsTakesExactlyThoseMessagesAndRemovesItsStagingQueue() throws Exception {
        String src = queue(p, "move.ids.src");
        String dst = queue(d, "move.ids.dst");
        p.produce(src, 12);
        List<Long> chosen = p.messageIds(src).subList(2, 7);

        TransferRunView run = awaitEnded(execute(preview(TransferMode.MOVE, src, ids(chosen), dst)));

        assertThat(run.state()).as(run.lastError()).isEqualTo(TransferState.SUCCEEDED);
        assertThat(run.delivered()).isEqualTo(5);
        assertThat(p.depth(src)).isEqualTo(7);
        List<Message> arrived = d.drain(dst);
        assertThat(arrived).extracting(m -> m.getIntProperty("seq")).containsExactly(2, 3, 4, 5, 6);
        assertThat(arrived)
                .extracting(m -> m.getLongProperty("_studio_orig_message_id"))
                .containsExactlyElementsOf(chosen);
        assertThat(staging.list(p.jolokia())).doesNotContain(StagingQueues.queueName(run.id()));
    }

    @Test
    void aMoveByFilterTakesOnlyTheMatchingMessages() throws Exception {
        String src = queue(p, "move.filter.src");
        String dst = queue(d, "move.filter.dst");
        p.produce(src, 30, (session, i) -> {
            Message m = session.createTextMessage("m" + i);
            m.setIntProperty("seq", i);
            m.setStringProperty("region", i % 3 == 0 ? "eu" : "us");
            return m;
        });

        TransferRunView run = awaitEnded(execute(preview(TransferMode.MOVE, src, filter("region = 'eu'"), dst)));

        assertThat(run.state()).as(run.lastError()).isEqualTo(TransferState.SUCCEEDED);
        assertThat(run.estimate()).isEqualTo(10);
        assertThat(d.drain(dst))
                .hasSize(10)
                .allSatisfy(m -> assertThat(m.getStringProperty("region")).isEqualTo("eu"));
        assertThat(p.drain(src))
                .hasSize(20)
                .allSatisfy(m -> assertThat(m.getStringProperty("region")).isEqualTo("us"));
        assertThat(staging.list(p.jolokia())).doesNotContain(StagingQueues.queueName(run.id()));
    }

    @Test
    void aWholeQueueMoveEmptiesTheSourceInOrder() throws Exception {
        String src = queue(p, "move.all.src");
        String dst = queue(d, "move.all.dst");
        p.produce(src, 45);

        TransferRunView run = awaitEnded(execute(preview(TransferMode.MOVE, src, all(), dst)));

        assertThat(run.state()).as(run.lastError()).isEqualTo(TransferState.SUCCEEDED);
        assertThat(run.delivered()).isEqualTo(45);
        assertThat(run.held()).isZero();
        assertThat(p.depth(src)).isZero();
        assertThat(d.drain(dst))
                .extracting(m -> m.getIntProperty("seq"))
                .containsExactlyElementsOf(
                        java.util.stream.IntStream.range(0, 45).boxed().toList());
        assertThat(staging.list(p.jolokia())).doesNotContain(StagingQueues.queueName(run.id()));
    }

    /** 5.9: a producer keeps sending while the run moves; the run ends, having moved only what was there at t0. */
    @Test
    void aFrozenSelectionEndsWhileAProducerKeepsSending() throws Exception {
        String src = queue(p, "move.frozen.src");
        String dst = queue(d, "move.frozen.dst");
        p.produce(src, 40);
        AtomicBoolean producing = new AtomicBoolean(true);
        AtomicInteger sent = new AtomicInteger();
        Thread producer = Thread.ofVirtual().start(() -> keepSending(src, producing, sent));
        try {
            while (sent.get() < 5) {
                Thread.sleep(10);
            }
            TransferRunView preview = preview(TransferMode.MOVE, src, all(), dst);
            TransferRunView run = awaitEnded(execute(preview));
            producing.set(false);
            producer.join();

            assertThat(run.state()).as(run.lastError()).isEqualTo(TransferState.SUCCEEDED);
            long t0 = run.t0().toEpochMilli();
            List<Message> moved = d.drain(dst);
            List<Message> left = p.drain(src);
            assertThat(moved.size()).isGreaterThanOrEqualTo(45).isEqualTo(run.delivered());
            assertThat(moved).allSatisfy(m -> assertThat(m.getJMSTimestamp()).isLessThanOrEqualTo(t0));
            assertThat(left)
                    .isNotEmpty()
                    .allSatisfy(m -> assertThat(m.getJMSTimestamp()).isGreaterThan(t0));
            assertThat(moved.size() + left.size()).isEqualTo(40 + sent.get());
        } finally {
            producing.set(false);
            producer.join();
        }
    }

    private void keepSending(String queue, AtomicBoolean producing, AtomicInteger sent) {
        try (ActiveMQConnectionFactory factory = p.jms();
                Connection connection = factory.createConnection()) {
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            MessageProducer producer = session.createProducer(session.createQueue(queue));
            while (producing.get()) {
                producer.send(session.createTextMessage("live-" + sent.get()));
                sent.incrementAndGet();
                Thread.sleep(5);
            }
        } catch (JMSException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }
}
