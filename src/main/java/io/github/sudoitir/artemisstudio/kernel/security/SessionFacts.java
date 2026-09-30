package io.github.sudoitir.artemisstudio.kernel.security;

import jakarta.servlet.http.HttpServletRequest;
import java.io.Serializable;
import java.time.Instant;

/**
 * What a session knows about how its user got in, kept in the session for its whole life
 * (ADR-0142, ADR-0144). Every field but {@code signedInAt} may be null.
 *
 * @param authenticatedAt when the user last proved themselves in full, which is what step-up
 *     freshness measures; null means the session is not fresh and needs a step-up
 * @param mfaVerifiedAt when a second factor was last verified; null when none was
 * @param mfaMethod how it was verified; null when {@code mfaVerifiedAt} is
 * @param signedInAt when the session began, for the absolute lifetime
 */
public record SessionFacts(
        Instant authenticatedAt,
        Instant mfaVerifiedAt,
        Method mfaMethod,
        Instant signedInAt,
        String clientAddress,
        String userAgent)
        implements Serializable {

    /** How a second factor was verified. */
    public enum Method {
        TOTP,
        WEBAUTHN,
        RECOVERY_CODE,
        TRUSTED_DEVICE
    }

    private static final int MAX_USER_AGENT_LENGTH = 256;

    /** A sign-in that just proved the user in full, with no second factor. */
    public static SessionFacts signedIn(HttpServletRequest request) {
        Instant now = Instant.now();
        String userAgent = request.getHeader("User-Agent");
        if (userAgent != null && userAgent.length() > MAX_USER_AGENT_LENGTH) {
            userAgent = userAgent.substring(0, MAX_USER_AGENT_LENGTH);
        }
        return new SessionFacts(now, null, null, now, request.getRemoteAddr(), userAgent);
    }

    /** A sign-in that just proved the user in full and verified a second factor. */
    public static SessionFacts signedInWithSecondFactor(HttpServletRequest request, Method method) {
        return signedIn(request).withMfaVerified(Instant.now(), method);
    }

    public SessionFacts withAuthenticatedAt(Instant at) {
        return new SessionFacts(at, mfaVerifiedAt, mfaMethod, signedInAt, clientAddress, userAgent);
    }

    /** These facts after a second factor was verified {@code at}, by {@code method}. */
    public SessionFacts withMfaVerified(Instant at, Method method) {
        return new SessionFacts(authenticatedAt, at, method, signedInAt, clientAddress, userAgent);
    }
}
