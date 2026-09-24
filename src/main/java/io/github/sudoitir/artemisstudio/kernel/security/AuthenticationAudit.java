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

    /** Record that the current caller signed out. */
    void loggedOut();

    interface Attempt {
        void failed(String reason);

        void succeeded();
    }
}
