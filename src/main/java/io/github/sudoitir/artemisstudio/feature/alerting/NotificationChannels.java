package io.github.sudoitir.artemisstudio.feature.alerting;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import java.util.List;

/**
 * The notification channels an operator configured. Absent when the alerting feature is disabled, so a
 * plugin injects an {@code ObjectProvider<NotificationChannels>}.
 */
@PluginApi
public interface NotificationChannels {

    /** Every channel, by name. Carries no configuration and no secret. */
    List<ChannelSummary> list();
}
