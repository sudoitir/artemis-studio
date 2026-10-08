package io.github.sudoitir.artemisstudio.feature.alerting;

import io.github.sudoitir.artemisstudio.feature.alerting.internal.persistence.AlertDeliveryEntity;
import io.github.sudoitir.artemisstudio.feature.alerting.internal.persistence.AlertDeliveryRepository;
import io.github.sudoitir.artemisstudio.feature.alerting.internal.persistence.NotificationChannelRepository;
import io.github.sudoitir.artemisstudio.kernel.core.StudioProperties;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginScopedBeans;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Queues plugins' notices on the alert delivery queue. A plugin gets an {@link OutboundNotices} bound to its id
 * ({@link #beansFor}), so it cannot send as anyone else.
 *
 * <p>The row is written in the caller's transaction. The hourly limit counts the source's rows from the last
 * hour, under a transaction-scoped advisory lock on the source so that concurrent senders cannot both pass it.
 * A notice's dedupe key is unique per source, so queueing it again does nothing. The stored payload is the exact
 * body a webhook receives.
 */
@Service
@RequiredArgsConstructor
public class OutboundNoticeService implements PluginScopedBeans {

    static final String BEAN_NAME = "outboundNotices";
    static final String TYPE = "notice";

    private final AlertDeliveryRepository deliveries;
    private final NotificationChannelRepository channels;
    private final StudioProperties studio;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;

    @Override
    public Map<String, Object> beansFor(String pluginId) {
        return Map.of(BEAN_NAME, new ScopedOutboundNotices(this, pluginId));
    }

    @Transactional
    public void enqueue(String source, UUID channelId, NoticeMessage notice, String dedupeKey) {
        if (source == null || source.isBlank() || channelId == null || notice == null) {
            throw new IllegalArgumentException("A notice needs a source, a channel and a message.");
        }
        if (dedupeKey == null || dedupeKey.isBlank() || dedupeKey.length() > OutboundNotices.MAX_DEDUPE_KEY) {
            throw new IllegalArgumentException(
                    "A notice needs a dedupe key of 1 to " + OutboundNotices.MAX_DEDUPE_KEY + " characters.");
        }
        if (!channels.existsById(channelId)) {
            throw new IllegalArgumentException("Unknown notification channel " + channelId + ".");
        }
        String payload = payload(source, notice, Instant.now());
        if (payload.getBytes(StandardCharsets.UTF_8).length > OutboundNotices.MAX_BYTES) {
            throw new IllegalArgumentException(
                    "A notice is larger than " + OutboundNotices.MAX_BYTES + " bytes as JSON.");
        }
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?))", rs -> {}, "alert_delivery.notice|" + source);
        int inserted = jdbc.update(
                "INSERT INTO alert_delivery (kind, source, channel_id, payload, dedupe_key)"
                        + " VALUES (?, ?, ?, ?::jsonb, ?) ON CONFLICT DO NOTHING",
                AlertDeliveryEntity.NOTICE,
                source,
                channelId,
                payload,
                dedupeKey);
        // A duplicate adds no row, so it is neither counted nor refused at the cap. The new row is counted, so a
        // notice past the cap rolls back with the caller's transaction.
        if (inserted == 1
                && deliveries.countBySourceAndCreatedAtGreaterThanEqual(
                                source, Instant.now().minus(Duration.ofHours(1)))
                        > OutboundNotices.MAX_PER_HOUR) {
            throw new IllegalArgumentException("Source " + source + " already sent " + OutboundNotices.MAX_PER_HOUR
                    + " notices in the last hour.");
        }
    }

    private String payload(String source, NoticeMessage notice, Instant at) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", TYPE);
        payload.put("source", source);
        payload.put("kind", AlertDeliveryEntity.NOTICE);
        payload.put("title", notice.title());
        payload.put("summary", notice.summary());
        payload.put("severity", notice.severity().name());
        List<Map<String, String>> facts = notice.facts().stream()
                .map(f -> Map.of("label", f.label(), "value", f.value()))
                .toList();
        payload.put("facts", facts);
        payload.put("url", notice.url() != null && studio.hasPublicUrl() ? studio.publicUrl() + notice.url() : null);
        payload.put("sentAt", at.toString());
        return mapper.writeValueAsString(payload);
    }
}
