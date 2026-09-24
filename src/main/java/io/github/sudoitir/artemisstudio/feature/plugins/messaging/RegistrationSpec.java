package io.github.sudoitir.artemisstudio.feature.plugins.messaging;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import java.util.UUID;

/**
 * What a plugin wants delivered (ADR-0111, ADR-0112).
 *
 * @param key the plugin's own name for the registration, 1 to 200 characters of letters, digits,
 *     {@code . _ : -}; registering the same key again replaces the registration
 * @param clusterId a registered cluster
 * @param queue the queue's name, on every serving node of the cluster
 * @param mode tap or consume
 * @param concurrency how many messages the plugin handles at once on each serving node: 1 to 32 for
 *     {@link RegistrationMode#CONSUME}, exactly 1 for {@link RegistrationMode#TAP}. Studio opens that
 *     many consumers per node, and each holds at most one unacknowledged message, so a slow plugin
 *     is handed fewer messages and the rest wait on the queue. Messages that share a group id
 *     ({@code JMSXGroupID}) stay in order at any concurrency, because the broker hands a group to
 *     one consumer (ADR-0112)
 * @param actingUserId the Studio user the registration acts for, whose cluster permissions it
 *     needs now and on every later pass
 */
@PluginApi
public record RegistrationSpec(
        String key, UUID clusterId, String queue, RegistrationMode mode, int concurrency, UUID actingUserId) {}
