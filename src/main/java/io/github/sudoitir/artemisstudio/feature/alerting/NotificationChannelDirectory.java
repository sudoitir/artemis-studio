package io.github.sudoitir.artemisstudio.feature.alerting;

import io.github.sudoitir.artemisstudio.feature.alerting.internal.persistence.NotificationChannelRepository;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link NotificationChannels}: the channel rows reduced to {@link ChannelSummary}. Marked {@link PluginApi} so the
 * plugin API context exports it: that context takes only beans whose own class carries the annotation.
 */
@Service
@PluginApi
@RequiredArgsConstructor
public class NotificationChannelDirectory implements NotificationChannels {

    private final NotificationChannelRepository channels;

    @Override
    @Transactional(readOnly = true)
    public List<ChannelSummary> list() {
        return channels.findAll(Sort.by("name")).stream()
                .map(c -> new ChannelSummary(c.getId(), c.getName(), c.getKind(), c.isEnabled()))
                .toList();
    }
}
