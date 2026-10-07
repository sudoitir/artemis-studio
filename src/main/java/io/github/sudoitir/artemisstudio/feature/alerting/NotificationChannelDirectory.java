package io.github.sudoitir.artemisstudio.feature.alerting;

import io.github.sudoitir.artemisstudio.feature.alerting.internal.persistence.NotificationChannelRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link NotificationChannels}: the channel rows reduced to {@link ChannelSummary}. */
@Service
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
