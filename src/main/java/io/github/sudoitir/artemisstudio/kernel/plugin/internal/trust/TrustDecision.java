package io.github.sudoitir.artemisstudio.kernel.plugin.internal.trust;

/**
 * Whether a jar's signer is a pinned publisher key (design.md §4). Computed on every read, never
 * stored. {@code fingerprint} and {@code subject} are the jar's signer (null when unsigned);
 * {@code keyName} is the trusted key's name, set only for {@link Status#TRUSTED}.
 */
public record TrustDecision(Status status, String fingerprint, String subject, String keyName) {

    public enum Status {
        TRUSTED,
        UNTRUSTED,
        UNSIGNED
    }
}
