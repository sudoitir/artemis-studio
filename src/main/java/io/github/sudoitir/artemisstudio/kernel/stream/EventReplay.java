package io.github.sudoitir.artemisstudio.kernel.stream;

import java.util.List;
import java.util.UUID;

/**
 * Replays a data-carrying topic to a client that reconnects with {@code Last-Event-ID}
 * (ADR-0027). Implemented by the module that owns the topic; the stream controller
 * sends the replay before live delivery resumes.
 */
public interface EventReplay {

    /** The topic this replay serves. */
    String topic();

    /** At most {@code cap} events after {@code lastEventId}, oldest first. */
    List<Replayed> since(UUID clusterId, long lastEventId, int cap);

    /** One replayed event and the SSE id it was first delivered with. */
    record Replayed(String id, Object data) {}
}
