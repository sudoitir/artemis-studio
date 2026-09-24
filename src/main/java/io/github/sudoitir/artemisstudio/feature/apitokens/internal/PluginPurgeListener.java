package io.github.sudoitir.artemisstudio.feature.apitokens.internal;

import io.github.sudoitir.artemisstudio.feature.apitokens.internal.persistence.ApiTokenGrantRepository;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginPurged;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * {@code kernel.plugin}'s purge SPI (design.md §4, task 6.8): {@code kernel.plugin} may not import
 * this module's {@code api_token_grant} table, so {@code PluginHost#purge} publishes {@link
 * PluginPurged} instead. {@code @EventListener} runs synchronously, on the calling thread, so this
 * still commits or rolls back with the rest of the purge transaction.
 */
@Component
@RequiredArgsConstructor
class PluginPurgeListener {

    private final ApiTokenGrantRepository grants;

    @EventListener
    void onPluginPurged(PluginPurged event) {
        grants.deleteByIdActionStartingWith(event.pluginId() + ":");
    }
}
