package io.github.sudoitir.artemisstudio.kernel.inbox;

import java.time.Instant;
import java.util.Map;

/** One notice as its recipient reads it. {@code readAt} is null while it is unread. */
public record InboxItem(
        long id,
        String source,
        String kind,
        Notice.Severity severity,
        String title,
        String body,
        String link,
        Map<String, String> data,
        Instant createdAt,
        Instant readAt) {}
