package io.github.sudoitir.artemisstudio.web;

import io.github.sudoitir.artemisstudio.service.NotificationChannelService;
import io.github.sudoitir.artemisstudio.web.dto.AlertViews.NotificationChannelRequest;
import io.github.sudoitir.artemisstudio.web.dto.AlertViews.NotificationChannelView;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Global notification channel CRUD and manual test delivery (alerting spec, ADR-0036). */
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

    @PostMapping("/{channelId}/test")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void test(@PathVariable UUID channelId) {
        channels.test(channelId);
    }
}
