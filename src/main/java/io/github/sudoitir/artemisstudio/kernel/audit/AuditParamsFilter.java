package io.github.sudoitir.artemisstudio.kernel.audit;

import java.util.Map;

/**
 * Masks sensitive values in audit parameters before they are written (ADR-0075 D6). Query text and
 * filter expressions carry literals, and the audit trail must never keep a sensitive one. The audit
 * kernel defines the seam; the content policy implements it. Without an implementation, parameters
 * are written as given.
 */
public interface AuditParamsFilter {

    /** The parameters to write. Must not change keys, only values. */
    Map<String, ?> filter(Map<String, ?> params);
}
