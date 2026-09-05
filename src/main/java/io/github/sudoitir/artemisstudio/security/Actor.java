package io.github.sudoitir.artemisstudio.security;

/**
 * Who is performing a mutating action (ADR-0041, superseding ADR-0023).
 * {@code username} is the authenticated principal's username or the literal
 * {@code "anonymous"}; {@code userId} is that principal's id when one is
 * authenticated, else {@code null}. Scheduler-originated actions use
 * {@link #system()}.
 */
public record Actor(String username, String sourceIp, String requestId, java.util.UUID userId, String tokenName) {

    public static final String ANONYMOUS = "anonymous";
    public static final String SYSTEM = "system";

    public Actor(String username, String sourceIp, String requestId, java.util.UUID userId) {
        this(username, sourceIp, requestId, userId, null);
    }

    public static Actor system() {
        return new Actor(SYSTEM, null, null, null, null);
    }

    /** The value written to {@code audit_event.username} — the owner, with the token name folded in when present. */
    public String displayName() {
        return tokenName == null ? username : username + " [token: " + tokenName + "]";
    }
}
