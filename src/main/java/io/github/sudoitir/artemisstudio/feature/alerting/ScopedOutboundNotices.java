package io.github.sudoitir.artemisstudio.feature.alerting;

import java.util.UUID;

/** A plugin's {@link OutboundNotices}: {@link OutboundNoticeService} with the plugin's id as the source. */
final class ScopedOutboundNotices implements OutboundNotices {

    private final OutboundNoticeService service;
    private final String pluginId;

    ScopedOutboundNotices(OutboundNoticeService service, String pluginId) {
        this.service = service;
        this.pluginId = pluginId;
    }

    @Override
    public void enqueue(UUID channelId, NoticeMessage notice, String dedupeKey) {
        service.enqueue(pluginId, channelId, notice, dedupeKey);
    }
}
