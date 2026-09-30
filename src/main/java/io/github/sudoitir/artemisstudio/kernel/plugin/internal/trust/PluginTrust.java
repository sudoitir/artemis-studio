package io.github.sudoitir.artemisstudio.kernel.plugin.internal.trust;

import io.github.sudoitir.artemisstudio.kernel.plugin.internal.host.PluginRefusedException;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence.TrustedKeyEntity;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence.TrustedKeyRepository;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.trust.TrustDecision.Status;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.validation.Signer;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.validation.Violation;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The trust store (design.md §3, §4): pinned publisher keys and the unverified allowance. A
 * decision is computed from the current keys on every call, so removing a key takes effect at once.
 */
@Component
public class PluginTrust {

    private final TrustedKeyRepository keys;
    private final JdbcTemplate jdbc;

    PluginTrust(TrustedKeyRepository keys, JdbcTemplate jdbc) {
        this.keys = keys;
        this.jdbc = jdbc;
    }

    public record TrustedKey(String fingerprint, String name, String subject, Instant addedAt, String addedBy) {}

    public TrustDecision decide(Signer signerOrNull) {
        return signerOrNull == null ? decide(null, null) : decide(signerOrNull.fingerprint(), signerOrNull.subject());
    }

    public TrustDecision decide(String fingerprintOrNull, String subject) {
        if (fingerprintOrNull == null) {
            return new TrustDecision(Status.UNSIGNED, null, null, null);
        }
        return keys.findById(fingerprintOrNull)
                .map(key -> new TrustDecision(Status.TRUSTED, fingerprintOrNull, subject, key.getName()))
                .orElseGet(() -> new TrustDecision(Status.UNTRUSTED, fingerprintOrNull, subject, null));
    }

    public boolean allowUnverified() {
        return Boolean.TRUE.equals(
                jdbc.queryForObject("SELECT allow_unverified FROM plugin_trust_policy WHERE id = 1", Boolean.class));
    }

    public void setAllowUnverified(boolean allow, String actor) {
        jdbc.update(
                "UPDATE plugin_trust_policy SET allow_unverified = ?, changed_at = now(), changed_by = ? WHERE id = 1",
                allow,
                actor);
    }

    public List<TrustedKey> keys() {
        return keys.findAllByOrderByAddedAtAsc().stream()
                .map(k ->
                        new TrustedKey(k.getFingerprint(), k.getName(), k.getSubject(), k.getAddedAt(), k.getAddedBy()))
                .toList();
    }

    @Transactional
    public TrustedKey add(String name, Signer key, String actor) {
        if (keys.existsById(key.fingerprint())) {
            throw new PluginRefusedException(List.of(new Violation(
                    "key-exists",
                    "The key " + key.fingerprint() + " is already trusted",
                    "Remove the existing key first if you want to add it again")));
        }
        var saved =
                keys.saveAndFlush(new TrustedKeyEntity(key.fingerprint(), name, key.subject(), key.publicKey(), actor));
        return new TrustedKey(
                saved.getFingerprint(), saved.getName(), saved.getSubject(), saved.getAddedAt(), saved.getAddedBy());
    }

    @Transactional
    public boolean remove(String fingerprint) {
        return keys.deleteByFingerprint(fingerprint) > 0;
    }
}
