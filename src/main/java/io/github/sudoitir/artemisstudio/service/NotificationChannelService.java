package io.github.sudoitir.artemisstudio.service;

import io.github.sudoitir.artemisstudio.broker.notify.NotificationSender;
import io.github.sudoitir.artemisstudio.mapper.AlertViewMapper;
import io.github.sudoitir.artemisstudio.persist.AuditEventEntity;
import io.github.sudoitir.artemisstudio.persist.AuditService;
import io.github.sudoitir.artemisstudio.persist.NotificationChannelEntity;
import io.github.sudoitir.artemisstudio.persist.NotificationChannelRepository;
import io.github.sudoitir.artemisstudio.security.ActorResolver;
import io.github.sudoitir.artemisstudio.security.SecretVault;
import io.github.sudoitir.artemisstudio.web.dto.AlertViews.NotificationChannelRequest;
import io.github.sudoitir.artemisstudio.web.dto.AlertViews.NotificationChannelView;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Notification channel CRUD and the manual "send test" action. A channel's
 * secret (a Slack webhook URL, a webhook signing secret) is AES-GCM at rest via
 * {@link SecretVault}'s opaque-AAD overload (ADR-0036) and never returned in
 * plaintext.
 */
@Service
@RequiredArgsConstructor
public class NotificationChannelService {

    private static final Set<String> KINDS = Set.of("WEBHOOK", "SLACK");

    private final NotificationChannelRepository channels;
    private final AuditService audit;
    private final ActorResolver actorResolver;
    private final AlertViewMapper mapper;
    private final SecretVault vault;
    private final List<NotificationSender> senders;
    private final ObjectMapper json;

    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.security.Permissions).ALERT_READ)")
    @Transactional(readOnly = true)
    public List<NotificationChannelView> list() {
        return channels.findAllByOrderByName().stream().map(mapper::channel).toList();
    }

    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.security.Permissions).ALERT_WRITE)")
    @Transactional
    public NotificationChannelView create(NotificationChannelRequest request) {
        validateKind(request.kind());
        NotificationChannelEntity channel = new NotificationChannelEntity(
                request.name(), request.kind(), request.config() != null ? request.config() : "{}", null, null);
        channels.save(channel); // need the generated id before sealing the AAD
        if (request.secret() != null && !request.secret().isBlank()) {
            seal(channel, request.secret());
        }

        AuditEventEntity event = audit.begin(
                actorResolver.resolve(),
                "CREATE_NOTIFICATION_CHANNEL",
                "NOTIFICATION_CHANNEL",
                channel.getName(),
                null,
                null,
                Map.of("kind", channel.getKind()),
                false);
        audit.succeed(event, 1);
        return mapper.channel(channel);
    }

    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.security.Permissions).ALERT_WRITE)")
    @Transactional
    public NotificationChannelView update(UUID channelId, NotificationChannelRequest request) {
        validateKind(request.kind());
        NotificationChannelEntity channel = requireChannel(channelId);

        AuditEventEntity event = audit.begin(
                actorResolver.resolve(),
                "UPDATE_NOTIFICATION_CHANNEL",
                "NOTIFICATION_CHANNEL",
                channel.getName(),
                null,
                null,
                Map.of("kind", request.kind()),
                false);

        channel.setName(request.name());
        channel.setConfig(request.config() != null ? request.config() : "{}");
        channel.setEnabled(request.enabled());
        if (request.secret() != null && !request.secret().isBlank()) {
            seal(channel, request.secret());
        }
        channels.save(channel);

        audit.succeed(event, 1);
        return mapper.channel(channel);
    }

    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.security.Permissions).ALERT_WRITE)")
    @Transactional
    public void delete(UUID channelId) {
        NotificationChannelEntity channel = requireChannel(channelId);
        AuditEventEntity event = audit.begin(
                actorResolver.resolve(),
                "DELETE_NOTIFICATION_CHANNEL",
                "NOTIFICATION_CHANNEL",
                channel.getName(),
                null,
                null,
                Map.of(),
                false);
        channels.delete(channel); // cascades alert_rule_channel
        audit.succeed(event, 1);
    }

    /** Sends one test notification through the real sender, synchronously — not queued. */
    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.security.Permissions).ALERT_WRITE)")
    @Transactional
    public void test(UUID channelId) {
        NotificationChannelEntity channel = requireChannel(channelId);
        AuditEventEntity event = audit.begin(
                actorResolver.resolve(),
                "TEST_NOTIFICATION_CHANNEL",
                "NOTIFICATION_CHANNEL",
                channel.getName(),
                null,
                null,
                Map.of(),
                false);

        NotificationSender sender = senders.stream()
                .filter(s -> s.kind().equals(channel.getKind()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No sender for channel kind " + channel.getKind()));
        String secret = channel.getSecretCt() == null
                ? ""
                : vault.decrypt(
                        channel.getId() + "|" + channel.getKind(), channel.getSecretCt(), channel.getSecretNonce());
        String payload = json.writeValueAsString(Map.of(
                "ruleName",
                "Test notification",
                "severity",
                "INFO",
                "transitions",
                List.of(Map.of("subject", "test", "kind", "FIRED", "value", 0))));

        NotificationSender.Result result = sender.send(0L, channel.getConfig(), secret, payload);
        if (result.success()) {
            audit.succeed(event, 1);
        } else {
            audit.fail(event, result.error());
            throw new IllegalStateException("Test notification failed: " + result.error());
        }
    }

    private void seal(NotificationChannelEntity channel, String plaintext) {
        SecretVault.Sealed sealed = vault.encrypt(channel.getId() + "|" + channel.getKind(), plaintext);
        channel.replaceSecret(sealed.ciphertext(), sealed.nonce());
        channels.save(channel);
    }

    private void validateKind(String kind) {
        if (!KINDS.contains(kind)) {
            throw new IllegalArgumentException("unknown channel kind: " + kind);
        }
    }

    private NotificationChannelEntity requireChannel(UUID channelId) {
        return channels.findById(channelId).orElseThrow(() -> new NotFoundException("NotificationChannel", channelId));
    }
}
