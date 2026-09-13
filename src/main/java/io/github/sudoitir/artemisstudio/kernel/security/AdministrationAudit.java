package io.github.sudoitir.artemisstudio.kernel.security;

import java.util.Map;

/**
 * Where user and role administration is audited (non-negotiable #3). Implemented by the audit
 * module, which depends on security; security depends only on this, as with
 * {@link AuthenticationAudit}.
 */
public interface AdministrationAudit {

    /** Record a completed change made by the current caller, in the caller's transaction. */
    void changed(String action, String targetType, String targetName, Map<String, ?> params);
}
