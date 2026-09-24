package io.github.sudoitir.artemisstudio.kernel.security;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginPurged;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginScopedBeans;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.PluginSecretEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.PluginSecretRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores plugins' secrets and hands each plugin its own {@link PluginSecrets} (ADR-0111).
 *
 * <p>Every value is sealed by {@link SecretVault} with the additional authenticated data
 * {@code plugin|<id>|<name>}, so a ciphertext moved to another plugin or name fails to decrypt
 * rather than being read under the wrong owner. Plugin ids and names are validated here, so
 * the AAD cannot be made ambiguous with a separator.
 */
@Component
@RequiredArgsConstructor
public class PluginSecretStore implements PluginScopedBeans {

    static final String BEAN_NAME = "pluginSecrets";

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,200}");
    private static final int MAX_VALUE_BYTES = 64 * 1024;

    private final PluginSecretRepository secrets;
    private final SecretVault vault;
    private final AdministrationAudit audit;
    private final Clock clock;

    @Override
    public Map<String, Object> beansFor(String pluginId) {
        return Map.of(BEAN_NAME, new PluginSecrets(this, pluginId));
    }

    @Transactional
    void put(String pluginId, String name, String value) {
        requireName(name);
        if (value == null) {
            throw new IllegalArgumentException("A secret's value cannot be null; delete the secret instead.");
        }
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_VALUE_BYTES) {
            // The size is stated, the value is not.
            throw new IllegalArgumentException(
                    "The secret '" + name + "' is larger than " + MAX_VALUE_BYTES + " bytes and was not stored.");
        }
        SecretVault.Sealed sealed = vault.encrypt(aad(pluginId, name), value);
        PluginSecretEntity row =
                secrets.findByPluginIdAndName(pluginId, name).orElseGet(() -> new PluginSecretEntity(pluginId, name));
        boolean replaced = row.getId() != null;
        row.setCiphertext(sealed.ciphertext());
        row.setNonce(sealed.nonce());
        row.setUpdatedAt(clock.instant());
        secrets.save(row);
        audit.changed(
                replaced ? "PLUGIN_SECRET_REPLACE" : "PLUGIN_SECRET_SET",
                "PLUGIN_SECRET",
                pluginId + "/" + name,
                Map.of("plugin", pluginId));
    }

    @Transactional(readOnly = true)
    Optional<String> get(String pluginId, String name) {
        requireName(name);
        return secrets.findByPluginIdAndName(pluginId, name)
                .map(row -> vault.decrypt(aad(pluginId, name), row.getCiphertext(), row.getNonce()));
    }

    @Transactional
    boolean delete(String pluginId, String name) {
        requireName(name);
        boolean deleted = secrets.deleteByPluginIdAndName(pluginId, name) > 0;
        if (deleted) {
            audit.changed("PLUGIN_SECRET_DELETE", "PLUGIN_SECRET", pluginId + "/" + name, Map.of("plugin", pluginId));
        }
        return deleted;
    }

    @Transactional(readOnly = true)
    List<PluginSecrets.SecretInfo> list(String pluginId) {
        return secrets.findByPluginIdOrderByName(pluginId).stream()
                .map(row -> new PluginSecrets.SecretInfo(row.getName(), row.getUpdatedAt()))
                .toList();
    }

    /** Runs inside the purge's own transaction (see {@link PluginPurged}). */
    @EventListener
    public void onPurged(PluginPurged purged) {
        secrets.deleteByPluginId(purged.pluginId());
    }

    private static void requireName(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("A secret name is 1 to 200 letters, digits, '.', '_' or '-'.");
        }
    }

    private static String aad(String pluginId, String name) {
        return "plugin|" + pluginId + "|" + name;
    }
}
