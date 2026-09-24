package io.github.sudoitir.artemisstudio.feature.alerting.web;

import io.github.sudoitir.artemisstudio.feature.alerting.NotificationChannelService;
import io.github.sudoitir.artemisstudio.feature.alerting.web.AlertViews.AlertDeliveryView;
import io.github.sudoitir.artemisstudio.feature.alerting.web.AlertViews.ChannelTestRequest;
import io.github.sudoitir.artemisstudio.feature.alerting.web.AlertViews.ChannelTestResultView;
import io.github.sudoitir.artemisstudio.feature.alerting.web.AlertViews.NotificationChannelRequest;
import io.github.sudoitir.artemisstudio.feature.alerting.web.AlertViews.NotificationChannelView;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Global notification channels, their tests and delivery log (alerting spec, ADR-0036, ADR-0105). */
@RestController
@RequestMapping("/api/v1/channels")
@RequiredArgsConstructor
public class NotificationChannelsController {

    private final NotificationChannelService channels;

    @GetMapping
    public List<NotificationChannelView> list() {
        return channels.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public NotificationChannelView create(@Valid @RequestBody NotificationChannelRequest request) {
        return channels.create(request);
    }

    @PutMapping("/{channelId}")
    public NotificationChannelView update(
            @PathVariable UUID channelId, @Valid @RequestBody NotificationChannelRequest request) {
        return channels.update(channelId, request);
    }

    @DeleteMapping("/{channelId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID channelId) {
        channels.delete(channelId);
    }

    /** A failed test is a result, not an error: 200 either way, with the cause in the body. */
    @PostMapping("/{channelId}/test")
    public ChannelTestResultView test(@PathVariable UUID channelId) {
        return channels.test(channelId);
    }

    /** Tests a configuration before it is saved; see {@link ChannelTestRequest}. */
    @PostMapping("/test")
    public ChannelTestResultView testConfiguration(@Valid @RequestBody ChannelTestRequest request) {
        return channels.test(request);
    }

    @GetMapping("/{channelId}/deliveries")
    public List<AlertDeliveryView> deliveries(
            @PathVariable UUID channelId, @RequestParam(defaultValue = "50") int limit) {
        return channels.deliveries(channelId, limit);
    }

    @PostMapping("/{channelId}/deliveries/{seq}/retry")
    public AlertDeliveryView retry(@PathVariable UUID channelId, @PathVariable long seq) {
        return channels.retry(channelId, seq);
    }
}
