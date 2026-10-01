package io.github.sudoitir.artemisstudio.kernel.plugin.internal.trust;

import io.github.sudoitir.artemisstudio.kernel.plugin.internal.host.PluginRefusedException;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence.PluginInstallRepository;
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

    /** The configuration list that pins keys, and the refusal code for removing one of them. */
    public static final String CONFIGURATION_KEY = "artemis-studio.plugins.trusted-keys";

    public static final String CONFIGURED_KEY = "configured-key";

    private final TrustedKeyRepository keys;
    private final PluginInstallRepository installs;
    private final JdbcTemplate jdbc;

    PluginTrust(TrustedKeyRepository keys, PluginInstallRepository installs, JdbcTemplate jdbc) {
        this.keys = keys;
        this.installs = installs;
        this.jdbc = jdbc;
    }

    public record TrustedKey(
            String fingerprint, String name, String subject, Instant addedAt, String addedBy, KeySource source) {}

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

    /**
     * Whether the installed plugin's signer is a trusted key right now, read from its install row on every
     * call. A plugin with no install row, or an unsigned one, is not verified.
     */
    public boolean verified(String pluginId) {
        return installs.findById(pluginId)
                .map(e -> decide(e.getSignerFingerprint(), e.getSignerSubject()).status() == Status.TRUSTED)
                .orElse(false);
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
        return keys.findAllByOrderByAddedAtAsc().stream().map(PluginTrust::view).toList();
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
        return view(saved);
    }

    private static TrustedKey view(TrustedKeyEntity k) {
        return new TrustedKey(
                k.getFingerprint(),
                k.getName(),
                k.getSubject(),
                k.getAddedAt(),
                k.getAddedBy(),
                KeySource.valueOf(k.getSource()));
    }

    /**
     * Removes an administrator-added key; {@code false} when there is none. A key the configuration pins is
     * refused, since the next start would pin it again.
     */
    @Transactional
    public boolean remove(String fingerprint) {
        var key = keys.findById(fingerprint);
        if (key.isPresent() && KeySource.CONFIGURATION.name().equals(key.get().getSource())) {
            throw new PluginRefusedException(List.of(new Violation(
                    CONFIGURED_KEY,
                    "The key " + fingerprint + " is set by " + CONFIGURATION_KEY
                            + ". Remove it there and restart Studio.",
                    "Remove it from " + CONFIGURATION_KEY + " and restart Studio")));
        }
        return keys.deleteByFingerprint(fingerprint) > 0;
    }

    /**
     * Pins a key from the configuration: adds it, or makes an existing key (an administrator's, or one
     * with another name) a configured one under {@code name}. Safe when several replicas start at once.
     */
    public void pin(String name, Signer key, String actor) {
        jdbc.update("""
                INSERT INTO plugin_trusted_key (fingerprint, name, subject, public_key, added_by, source)
                VALUES (?, ?, ?, ?, ?, 'CONFIGURATION')
                ON CONFLICT (fingerprint) DO UPDATE SET name = EXCLUDED.name, source = 'CONFIGURATION'
                """, key.fingerprint(), name, key.subject(), key.publicKey(), actor);
    }

    /** Removes a key the configuration no longer pins; an administrator's key with that fingerprint stays. */
    public void unpin(String fingerprint) {
        jdbc.update("DELETE FROM plugin_trusted_key WHERE fingerprint = ? AND source = 'CONFIGURATION'", fingerprint);
    }
}
