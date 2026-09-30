package io.github.sudoitir.artemisstudio.kernel.security;

import java.time.Duration;

/**
 * How long a session may live (ADR-0144). The values are runtime settings, owned by the settings
 * module, which implements this so security can read them without depending on it.
 */
public interface SessionLifetimes {

    String IDLE_TIMEOUT = "security.session.idle-timeout";
    String ABSOLUTE_LIFETIME = "security.session.absolute-lifetime";

    /** How long a session may go without user activity. */
    Duration idleTimeout();

    /** How long after signing in a session ends, however active it is. */
    Duration absoluteLifetime();
}
