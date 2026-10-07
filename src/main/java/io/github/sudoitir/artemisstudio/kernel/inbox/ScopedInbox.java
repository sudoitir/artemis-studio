package io.github.sudoitir.artemisstudio.kernel.inbox;

import java.util.Collection;
import java.util.UUID;

/** A plugin's {@link Inbox}: {@link InboxService} with the plugin's id as the source of everything it posts. */
final class ScopedInbox implements Inbox {

    private final InboxService service;
    private final String pluginId;

    ScopedInbox(InboxService service, String pluginId) {
        this.service = service;
        this.pluginId = pluginId;
    }

    @Override
    public int post(Notice notice, Collection<UUID> recipients) {
        return service.post(pluginId, notice, recipients);
    }

    @Override
    public int postToHolders(Notice notice, String permission, UUID clusterId, Collection<UUID> exclude) {
        return service.postToHolders(pluginId, notice, permission, clusterId, exclude);
    }

    @Override
    public int resolve(String dedupeKey, String newTitle) {
        return service.resolve(pluginId, dedupeKey, newTitle);
    }
}
