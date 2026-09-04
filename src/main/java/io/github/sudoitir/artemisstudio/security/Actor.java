package io.github.sudoitir.artemisstudio.security;

/**
 * Who is performing a mutating action, resolved before authentication exists
 * (ADR-0023). {@code username} is the security principal or the literal
 * {@code "anonymous"}; {@code userId} is always {@code null} until Phase 8 adds a
 * user table. Scheduler-originated actions use {@link #system()}.
 */
public record Actor(String username, String sourceIp, String requestId, java.util.UUID userId) {

    public static final String ANONYMOUS = "anonymous";
    public static final String SYSTEM = "system";

    public static Actor system() {
        return new Actor(SYSTEM, null, null, null);
    }
}
