package io.github.sudoitir.artemisstudio.feature.plugins;

import static io.github.sudoitir.artemisstudio.kernel.plugin.internal.trust.PluginTrust.CONFIGURATION_KEY;

import io.github.sudoitir.artemisstudio.kernel.audit.AuditEvent;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditService;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginProperties;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.trust.KeySource;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.trust.PluginTrust;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.trust.PublisherKeys;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.validation.Signer;
import io.github.sudoitir.artemisstudio.kernel.security.Actor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Makes the trusted key table agree with {@code artemis-studio.plugins.trusted-keys} (ADR-0166). It is an
 * {@link ApplicationRunner}, which Spring Boot runs before it publishes {@code ApplicationReadyEvent}, and
 * the plugin host starts its plugins only on that event, so a plugin signed by a configured key is
 * trusted when it starts at boot. An entry that cannot be used fails startup, naming its index.
 *
 * <p>A configured key is added, or an administrator's key with the same fingerprint becomes a configured
 * one; a configured key that is no longer listed is removed, which un-trusts it as removing any key does
 * (ADR-0141). Each change is audited as {@code PLUGIN_KEY_ADD} or {@code PLUGIN_KEY_REMOVE} by the actor
 * {@value #ACTOR}, with the fingerprint.
 */
@Component
@RequiredArgsConstructor
class TrustedKeyReconciler implements ApplicationRunner {

    static final String ACTOR = "configuration";

    private static final String KEY_ADD = "PLUGIN_KEY_ADD";

    private static final Actor CONFIGURATION = new Actor(ACTOR, null, null, null);

    private final PluginProperties properties;
    private final PluginTrust trust;
    private final AuditService audit;

    @Override
    public void run(ApplicationArguments args) {
        reconcile();
    }

    void reconcile() {
        Map<String, Configured> wanted = configured();
        Map<String, PluginTrust.TrustedKey> existing = new LinkedHashMap<>();
        trust.keys().forEach(k -> existing.put(k.fingerprint(), k));

        wanted.forEach((fingerprint, key) -> {
            PluginTrust.TrustedKey current = existing.get(fingerprint);
            if (current == null) {
                audited(KEY_ADD, key.name(), fingerprint, "add", () -> trust.pin(key.name(), key.signer(), ACTOR));
            } else if (current.source() == KeySource.ADMIN) {
                audited(KEY_ADD, key.name(), fingerprint, "convert", () -> trust.pin(key.name(), key.signer(), ACTOR));
            } else if (!current.name().equals(key.name())) {
                audited(KEY_ADD, key.name(), fingerprint, "rename", () -> trust.pin(key.name(), key.signer(), ACTOR));
            }
        });
        existing.values().stream()
                .filter(k -> k.source() == KeySource.CONFIGURATION && !wanted.containsKey(k.fingerprint()))
                .forEach(k -> audited(
                        "PLUGIN_KEY_REMOVE",
                        k.fingerprint(),
                        k.fingerprint(),
                        "remove",
                        () -> trust.unpin(k.fingerprint())));
    }

    private record Configured(String name, Signer signer) {}

    /** The entries by fingerprint, or an {@link IllegalStateException} naming the first bad entry's index. */
    private Map<String, Configured> configured() {
        Map<String, Configured> wanted = new LinkedHashMap<>();
        Map<String, Integer> indexOf = new LinkedHashMap<>();
        List<PluginProperties.TrustedKey> entries = properties.trustedKeys();
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            String where = CONFIGURATION_KEY + "[" + i + "]";
            if (entry == null || entry.name() == null || entry.name().isBlank()) {
                throw new IllegalStateException(where + ": name is required, it is how the key is listed in the UI");
            }
            if (entry.pem() == null || entry.pem().isBlank()) {
                throw new IllegalStateException(where + " ('" + entry.name() + "'): pem is required");
            }
            Signer signer;
            try {
                signer = PublisherKeys.parse(entry.pem());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(where + " ('" + entry.name() + "'): " + e.getMessage(), e);
            }
            Integer first = indexOf.putIfAbsent(signer.fingerprint(), i);
            if (first != null) {
                throw new IllegalStateException(where + " ('" + entry.name() + "'): the same key as "
                        + CONFIGURATION_KEY + "[" + first + "], fingerprint " + signer.fingerprint());
            }
            wanted.put(signer.fingerprint(), new Configured(entry.name().strip(), signer));
        }
        return wanted;
    }

    /** The audit row opens first and records the outcome, so a failed change is on record too. */
    private void audited(String action, String target, String fingerprint, String change, Runnable work) {
        AuditEvent event = audit.begin(
                CONFIGURATION,
                action,
                "plugin",
                target,
                null,
                null,
                Map.of("source", ACTOR, "change", change, "fingerprint", fingerprint),
                false);
        try {
            work.run();
            audit.succeed(event, 1);
        } catch (RuntimeException e) {
            audit.fail(event, e.getMessage());
            throw e;
        }
    }
}
