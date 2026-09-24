package io.github.sudoitir.artemisstudio.feature.plugins.messaging;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import java.util.UUID;

/**
 * What a plugin wants delivered (ADR-0111).
 *
 * @param key the plugin's own name for the registration, 1 to 200 characters of letters, digits,
 *     {@code . _ : -}; registering the same key again replaces the registration
 * @param clusterId a registered cluster
 * @param queue the queue's name, on every serving node of the cluster
 * @param mode tap or consume
 * @param actingUserId the Studio user the registration acts for, whose cluster permissions it
 *     needs now and on every later pass
 */
@PluginApi
public record RegistrationSpec(String key, UUID clusterId, String queue, RegistrationMode mode, UUID actingUserId) {}
