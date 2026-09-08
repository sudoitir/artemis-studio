package io.github.sudoitir.artemisstudio.broker.capture;

import io.github.sudoitir.artemisstudio.persist.MessageIndexWriter;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The message index's subscription to the capture bus. Separate from the consumer so
 * that draining a broker queue and writing a row are independently replaceable, and
 * so the bus has no compile-time knowledge of what is listening to it.
 *
 * <p>Rows are buffered and written in one round trip, because a partitioned table with
 * three GIN indexes cannot absorb them one at a time at capture rate (ADR-0062 D7). The
 * buffer is bounded and is drained by {@link #flush()}, which a consumer calls before
 * it acknowledges — so nothing is acknowledged on the broker that is not committed here.
 */
@Component
@RequiredArgsConstructor
public class CaptureIndexSink implements CaptureBus.Listener {

    /** Rows written per round trip. Matched to the consumer's acknowledge batch. */
    private static final int BATCH = 50;

    private final CaptureBus bus;
    private final MessageIndexWriter writer;

    private final List<MessageIndexWriter.Captured> buffered = new ArrayList<>();

    @PostConstruct
    void subscribe() {
        bus.register(this);
    }

    @Override
    public void captured(CaptureBus.Captured captured) {
        List<MessageIndexWriter.Captured> batch = null;
        synchronized (buffered) {
            buffered.add(new MessageIndexWriter.Captured(
                    captured.clusterId(),
                    captured.row(),
                    captured.origAddress(),
                    captured.sourceMessageId(),
                    captured.at()));
            if (buffered.size() >= BATCH) {
                batch = drain();
            }
        }
        if (batch != null) {
            writer.capturedBatch(batch);
        }
    }

    @Override
    public void flush() {
        List<MessageIndexWriter.Captured> batch;
        synchronized (buffered) {
            batch = drain();
        }
        writer.capturedBatch(batch);
    }

    private List<MessageIndexWriter.Captured> drain() {
        List<MessageIndexWriter.Captured> batch = List.copyOf(buffered);
        buffered.clear();
        return batch;
    }
}
