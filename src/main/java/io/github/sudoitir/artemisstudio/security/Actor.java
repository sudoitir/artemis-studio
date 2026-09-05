package io.github.sudoitir.artemisstudio.security;

/**
 * Who is performing a mutating action (ADR-0041, superseding ADR-0023).
 * {@code username} is the authenticated principal's username or the literal
 * {@code "anonymous"}; {@code userId} is that principal's id when one is
 * authenticated, else {@code null}. Scheduler-originated actions use
 * {@link #system()}.
 */
public record Actor(String username, String sourceIp, String requestId, java.util.UUID userId) {

    public static final String ANONYMOUS = "anonymous";
    public static final String SYSTEM = "system";

    public static Actor system() {
        return new Actor(SYSTEM, null, null, null);
    }
}
