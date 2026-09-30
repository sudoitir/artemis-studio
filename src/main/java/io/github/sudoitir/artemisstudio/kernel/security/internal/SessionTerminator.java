package io.github.sudoitir.artemisstudio.kernel.security.internal;

import io.github.sudoitir.artemisstudio.kernel.security.SessionEnded;
import java.util.Collection;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Ends every session of a user whose access was taken away (ADR-0123). A session holds the grants
 * resolved at sign-in, so without this a disabled user, or one whose grant or role was narrowed,
 * keeps the old rights until the session times out. Sessions are found through Spring Session's
 * principal-name index, which is the username ({@code StudioPrincipal extends User}), and are
 * deleted after the revoking transaction commits, so a rolled-back change signs nobody out.
 */
@Component
@RequiredArgsConstructor
public class SessionTerminator {

    private final FindByIndexNameSessionRepository<? extends Session> sessions;
    private final ApplicationEventPublisher events;

    void endSessionsOf(Collection<String> usernames) {
        if (usernames.isEmpty()) {
            return;
        }
        afterCommit(() -> {
            for (String username : usernames) {
                sessions.findByPrincipalName(username).keySet().forEach(this::delete);
            }
        });
    }

    /** Ends every session of the user except those in {@code keepSessionIds}. */
    public void endSessionsOfExcept(String username, Collection<String> keepSessionIds) {
        afterCommit(() -> sessions.findByPrincipalName(username).keySet().stream()
                .filter(id -> !keepSessionIds.contains(id))
                .forEach(this::delete));
    }

    /** Deletes one session and tells this instance's open streams of it to stop. */
    void delete(String sessionId) {
        sessions.deleteById(sessionId);
        events.publishEvent(new SessionEnded(sessionId));
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
