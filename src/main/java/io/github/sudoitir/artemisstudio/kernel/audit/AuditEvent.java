package io.github.sudoitir.artemisstudio.kernel.audit;

/**
 * An audit event as other modules see it: the handle {@link AuditService#begin} returns, to pass
 * back with the outcome, and the entries {@link AuditService#history} lists.
 */
public interface AuditEvent {

    Long getId();

    String getAction();

    String getTargetName();

    String getOutcome();
}
