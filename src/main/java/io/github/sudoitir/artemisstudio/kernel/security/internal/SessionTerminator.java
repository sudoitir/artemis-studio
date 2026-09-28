package io.github.sudoitir.artemisstudio.kernel.security.internal;

import java.util.Collection;
import lombok.RequiredArgsConstructor;
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
class SessionTerminator {

    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    void endSessionsOf(Collection<String> usernames) {
        if (usernames.isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    delete(usernames);
                }
            });
        } else {
            delete(usernames);
        }
    }

    private void delete(Collection<String> usernames) {
        for (String username : usernames) {
            sessions.findByPrincipalName(username).keySet().forEach(sessions::deleteById);
        }
    }
}
