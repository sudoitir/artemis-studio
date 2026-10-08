package io.github.sudoitir.artemisstudio.kernel.security;

import io.github.sudoitir.artemisstudio.kernel.replica.BusResumed;
import io.github.sudoitir.artemisstudio.kernel.replica.ReplicaSignal;
import io.github.sudoitir.artemisstudio.kernel.replica.StudioBus;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Where a change to what anyone may do is announced: a grant, a role's permissions, a team, its
 * patterns, members or shares, a user's groups, a disabled account. The announcement makes every
 * replica drop the access it cached, so the change applies on the next request, with no new sign-in.
 * The local caches are dropped when the caller's transaction commits, and the other replicas'
 * when they receive the bus message, which is also sent only on commit.
 *
 * <p>Each change also writes an {@code access_change_log} row in the caller's transaction (ADR-0181),
 * naming who made it and, when one user's access changed, whose. A change with no signed-in user behind
 * it, such as a directory sign-in syncing its own groups, writes none: the approval rules only ask what
 * a user changed.
 */
@Component
@RequiredArgsConstructor
public class AccessChanges {

    static final String SIGNAL = "access-changed";

    private final AccessLoader access;
    private final TeamIndex teams;
    private final StudioBus bus;
    private final ActorResolver actors;
    private final JdbcClient jdbc;

    /** Everyone's access may have changed (a role, a team, a share). */
    public void changed() {
        announce(null);
    }

    /** One user's access changed (a grant, their groups, their account). */
    public void changedFor(UUID userId) {
        announce(userId);
    }

    private void announce(UUID userId) {
        log(userId);
        bus.publish(new ReplicaSignal(SIGNAL, userId == null ? null : userId.toString()));
        afterCommit(() -> drop(userId));
    }

    private void log(UUID subjectId) {
        UUID actorId = actors.resolve().userId();
        if (actorId != null) {
            jdbc.sql("INSERT INTO access_change_log (actor_id, subject_id) VALUES (?, ?)")
                    .params(actorId, subjectId)
                    .update();
        }
    }

    @EventListener(condition = "#signal.kind() == 'access-changed'")
    void onSignal(ReplicaSignal signal) {
        drop(signal.key() == null ? null : UUID.fromString(signal.key()));
    }

    /** The bus was down: a change may have been announced in the gap. */
    @EventListener
    void onBusResumed(BusResumed resumed) {
        drop(null);
    }

    private void drop(UUID userId) {
        access.invalidate(userId);
        // Ownership, shares and role permissions all feed the index, and a user change touches none of them.
        if (userId == null) {
            teams.invalidate();
        }
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
