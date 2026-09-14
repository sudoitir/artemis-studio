package io.github.sudoitir.artemisstudio.platform.governance;

/** The permissions the content policy checks (ADR-0075). */
public final class GovernancePermissions {

    /** Sensitive message values in clear at the granted scope. Credentials are never shown. */
    public static final String MESSAGE_CLEAR = "message:clear";

    public static final String GOVERNANCE_READ = "governance:read";
    public static final String GOVERNANCE_WRITE = "governance:write";

    private GovernancePermissions() {}
}
