package io.github.sudoitir.artemisstudio.kernel.plugin.internal.host;

import io.github.sudoitir.artemisstudio.kernel.plugin.internal.trust.TrustDecision;

/**
 * design.md §5: who signed the jar a plan is for and whether that may run. {@code fingerprint},
 * {@code subject} and {@code keyName} are as in {@link TrustDecision}; {@code previousFingerprint} is
 * the installed version's signer ({@code null} on a fresh install or when it was unsigned);
 * {@code allowed} is {@code true} for a trusted signer, or whenever the unverified allowance is on.
 */
public record PlanTrust(
        TrustDecision.Status status,
        String fingerprint,
        String subject,
        String keyName,
        String previousFingerprint,
        boolean signerChanged,
        boolean allowed) {}
