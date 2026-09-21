package io.github.sudoitir.artemisstudio.feature.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.feature.transfer.web.TransferViews.TransferRunView;
import io.github.sudoitir.artemisstudio.platform.broker.NodeCallLimiter;
import io.github.sudoitir.artemisstudio.platform.broker.StagingQueues;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 5.8: a whole queue of 100,000 messages moves to another cluster. The messages parked in staging never
 * exceed twice the batch size, and Studio's management calls to each node stay within its limiter.
 * Small bodies keep the suite's runtime down; the size of a message is not what is under test.
 */
@Tag("large")
class TransferLargeQueueTest extends TransferTestSupport {

    private static final int COUNT = 100_000;
    private static final int BATCH = 1_000;

    @Autowired
    MeterRegistry meters;

    @Autowired
    NodeCallLimiter limiter;

    private double requests(String node) {
        var counter = meters.find("studio.broker.requests").tag("node", node).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void aHundredThousandMessagesMoveWithBoundedStagingWithinTheLimiter() throws Exception {
        settings.put(TransferSettings.BATCH_SIZE, Integer.toString(BATCH));
        String src = queue(p, "large.src");
        String dst = queue(d, "large.dst");
        p.produce(src, COUNT);
        assertThat(p.depth(src)).isEqualTo(COUNT);

        TransferRunView preview = preview(TransferMode.MOVE, src, all(), dst);
        assertThat(preview.estimate()).isEqualTo(COUNT);
        String stagingQueue = StagingQueues.queueName(preview.id());
        AtomicBoolean sampling = new AtomicBoolean(true);
        AtomicLong deepest = new AtomicLong();
        Thread sampler = Thread.ofVirtual().start(() -> {
            while (sampling.get()) {
                try {
                    deepest.accumulateAndGet(p.depth(stagingQueue), Math::max);
                } catch (RuntimeException notYetCreated) {
                    // before the run creates staging, and after it removes it
                }
            }
        });
        double sourceCallsBefore = requests(p.jolokiaUrl());
        double targetCallsBefore = requests(d.jolokiaUrl());
        long started = System.nanoTime();
        TransferRunView run;
        try {
            run = awaitEnded(execute(preview, true), Duration.ofMinutes(5));
        } finally {
            sampling.set(false);
            sampler.join();
        }
        double seconds = (System.nanoTime() - started) / 1e9;

        assertThat(run.state()).as(run.lastError()).isEqualTo(TransferState.SUCCEEDED);
        assertThat(run.delivered()).isEqualTo(COUNT);
        assertThat(d.depth(dst)).isEqualTo(COUNT);
        assertThat(p.depth(src)).isZero();
        // The broker's MessageCount sums its pending, delivering and scheduled counts without a lock
        // (QueueImpl.getMessageCount), so a message being handed to the relay can be counted twice
        // for an instant. The bound is twice the batch; one more is that double count.
        assertThat(deepest.get())
                .as("messages parked in staging at once")
                .isPositive()
                .isLessThanOrEqualTo(2L * BATCH + 1);
        // A node's bucket holds a second's permits and is refilled once a second.
        double ceiling = limiter.permitsPerSecond() * (Math.ceil(seconds) + 1);
        assertThat(requests(p.jolokiaUrl()) - sourceCallsBefore).isPositive().isLessThanOrEqualTo(ceiling);
        assertThat(requests(d.jolokiaUrl()) - targetCallsBefore).isPositive().isLessThanOrEqualTo(ceiling);
        System.out.printf("5.8: %d messages moved in %.1f s%n", COUNT, seconds);
    }
}
