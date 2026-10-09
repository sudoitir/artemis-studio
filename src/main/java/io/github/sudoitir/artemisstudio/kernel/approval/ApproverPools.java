package io.github.sudoitir.artemisstudio.kernel.approval;

import io.github.sudoitir.artemisstudio.kernel.gate.ApprovalProvider;
import io.github.sudoitir.artemisstudio.kernel.gate.ApprovalProviderRegistry;
import io.github.sudoitir.artemisstudio.kernel.gate.ApproverPool;
import io.github.sudoitir.artemisstudio.kernel.security.PermissionHolders;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The {@link ApproverPool}: the enabled users the armed provider's approver permission reaches for the whole
 * installation, decided by the same access snapshots every request is (so a user who holds it through a team or a
 * directory group counts). It stops counting at a few more than the quorum, which is all anyone asks.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class ApproverPools implements ApproverPool {

    private static final int COUNT_UP_TO = 50;

    private final ApprovalProviderRegistry providers;
    private final PermissionHolders holders;

    @Override
    public int size() {
        return providers
                .armedApproverPermission()
                .map(permission ->
                        holders.holders(null, permission, COUNT_UP_TO).size())
                .orElse(0);
    }

    /** Whether the armed provider holds anything now; a provider that cannot say, or is not here, enforces. */
    boolean enforcing() {
        if (providers.armedProviderId().isEmpty()) {
            return false;
        }
        try {
            return providers.attached().map(ApprovalProvider::enforcing).orElse(true);
        } catch (RuntimeException e) {
            log.warn("approval-gate provider could not say whether it enforces; treating it as enforcing", e);
            return true;
        }
    }
}
