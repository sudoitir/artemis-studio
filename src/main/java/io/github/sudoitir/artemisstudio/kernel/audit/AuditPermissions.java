package io.github.sudoitir.artemisstudio.kernel.audit;

/** The permission the audit module declares and checks. */
public final class AuditPermissions {

    /** Export a cluster's audit trail as a file. What the file holds is still limited to what the caller may read. */
    public static final String EXPORT = "audit:export";

    private AuditPermissions() {}
}
