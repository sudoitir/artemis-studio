package io.github.sudoitir.rahdar.kernel.auditfeed;

/**
 * The audit purge cannot tell what it must keep, so it deletes nothing: a retention hold did not
 * answer, answered too much, or its plugin is not running here (ADR-0190). The message says why and
 * is shown as the purge's last error.
 */
public class AuditPurgeRefused extends RuntimeException {

    public AuditPurgeRefused(String message) {
        super(message);
    }

    public AuditPurgeRefused(String message, Throwable cause) {
        super(message, cause);
    }
}
