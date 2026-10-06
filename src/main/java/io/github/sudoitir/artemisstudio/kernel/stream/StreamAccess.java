package io.github.sudoitir.artemisstudio.kernel.stream;

import java.util.UUID;

/**
 * Decides what one subscriber may see of a frame, when the frame is written to them, so a change of
 * their access applies to the next frame of a stream that stays open.
 */
@FunctionalInterface
public interface StreamAccess {

    /**
     * The frame as {@code subscriber} may see it: whole, with the resources it names cut to those they
     * may read, or {@code null} when none of it is for them.
     */
    Subscriber.Held narrow(Subscriber subscriber, UUID clusterId, Subscriber.Held frame);
}
