package io.github.sudoitir.artemisstudio.kernel.stream;

import io.github.sudoitir.artemisstudio.kernel.replica.ReplicaSignal;
import io.github.sudoitir.artemisstudio.kernel.replica.StudioBus;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Nudges one user's open streams on every replica ({@link UserStreamHub}). A signal carries no data:
 * the client refetches what the kind names. Sent inside the caller's transaction, it reaches the
 * replicas only if that transaction commits.
 */
@Component
public class UserSignals {

    /** The user's inbox changed. */
    public static final String INBOX = "inbox";

    /** The operations held for the user's decision changed. */
    public static final String HELD = "held";

    private final StudioBus bus;

    public UserSignals(StudioBus bus) {
        this.bus = bus;
    }

    /** Whether {@code kind} is one a user stream carries. */
    static boolean isUserKind(String kind) {
        return INBOX.equals(kind) || HELD.equals(kind);
    }

    public void signal(String kind, UUID userId) {
        if (!isUserKind(kind)) {
            throw new IllegalArgumentException("Unknown user signal '" + kind + "'");
        }
        bus.publish(new ReplicaSignal(kind, userId.toString()));
    }
}
