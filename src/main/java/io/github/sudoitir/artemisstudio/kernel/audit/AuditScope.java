package io.github.sudoitir.artemisstudio.kernel.audit;

/**
 * The audit event an action is one part of (ADR-0093). While {@link #PARENT} is bound, every
 * event {@link AuditService#begin} writes names it as its parent, so a bulk run's per-queue
 * events link to the run without any command or service taking a new parameter.
 */
public final class AuditScope {

    /** The parent audit event's id. */
    public static final ScopedValue<Long> PARENT = ScopedValue.newInstance();

    private AuditScope() {}
}
