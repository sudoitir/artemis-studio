package io.github.sudoitir.artemisstudio.kernel.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Where sign-in and sign-out are audited (non-negotiable #3). Implemented by the audit module,
 * which depends on security; the login path depends only on this.
 */
public interface AuthenticationAudit {

    /** Record a login attempt before it is decided; finish it with the outcome. */
    Attempt loginAttempted(String username, HttpServletRequest request);

    /** Record a signed-in caller proving who they are again (step-up, ADR-0103); finish it with the outcome. */
    Attempt reauthenticationAttempted(HttpServletRequest request);

    /** Record that repeated failed sign-ins just locked the account; there is no session, so the caller is anonymous. */
    void accountLocked(String username, HttpServletRequest request);

    /** Record a second factor that was wrong, already used or throttled; the caller is the signed-in user of a step-up, else anonymous. */
    void secondFactorFailed(String username, String reason);

    /** Record a second factor that completed a sign-in or step-up. */
    void secondFactorVerified(String username, SessionFacts.Method method);

    /**
     * Record that a sign-in whose password was accepted was completed by its second factor: the login
     * attempt {@link Attempt#awaitingSecondFactor} closed as not succeeded now succeeded.
     */
    void loginCompleted(long attemptId);

    /** Record that the current caller signed out. */
    void loggedOut();

    interface Attempt {
        void failed(String reason);

        void succeeded();

        /**
         * The password was accepted and a second factor is still owed. The attempt is closed as not
         * succeeded, with that reason, so a factor that is never given, is wrong or expires leaves it so;
         * {@link #loginCompleted} turns it into a success once the factor is given.
         *
         * @return the attempt's id, to complete it with
         */
        long awaitingSecondFactor();
    }
}
