package io.github.sudoitir.artemisstudio.kernel.security;

/**
 * A session was ended on this instance: signed out, ended by its user or an administrator, or
 * revoked with the user's access. Long-lived responses tied to the session, such as event streams,
 * stop on it. A session that ends elsewhere, or by timing out, is found through
 * {@link SessionAuthentication#isLive}.
 */
public record SessionEnded(String sessionId) {}
