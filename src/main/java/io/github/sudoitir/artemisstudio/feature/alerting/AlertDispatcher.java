package io.github.sudoitir.artemisstudio.feature.alerting;

import io.github.sudoitir.artemisstudio.feature.alerting.internal.persistence.AlertDeliveryEntity;
import io.github.sudoitir.artemisstudio.feature.alerting.internal.persistence.AlertDeliveryRepository;
import io.github.sudoitir.artemisstudio.feature.alerting.internal.persistence.NotificationChannelEntity;
import io.github.sudoitir.artemisstudio.feature.alerting.internal.persistence.NotificationChannelRepository;
import io.github.sudoitir.artemisstudio.kernel.security.SecretVault;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Claims due {@code alert_delivery} rows and sends them (ADR-0036, design.md
 * decision 5). {@code claimDue}'s {@code FOR UPDATE SKIP LOCKED} makes this
 * safe under a concurrent instance with zero extra code — the multi-instance
 * seam ADR-0015 already left open.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AlertDispatcher {

    private static final int BATCH_SIZE = 20;

    private final AlertDeliveryRepository deliveries;
    private final NotificationChannelRepository channels;
    private final List<NotificationSender> senders;
    private final SecretVault vault;
    private final SettingsService settings;

    /** Scheduled by {@code JobScheduler} on {@code alerting.dispatch-interval}. */
    @Transactional
    public void dispatch() {
        for (AlertDeliveryEntity delivery : deliveries.claimDue(BATCH_SIZE)) {
            attempt(delivery);
        }
    }

    private void attempt(AlertDeliveryEntity delivery) {
        NotificationChannelEntity channel =
                channels.findById(delivery.getChannelId()).orElse(null);
        if (channel == null || !channel.isEnabled()) {
            delivery.recordDead("Channel no longer exists or is disabled");
            deliveries.save(delivery);
            return;
        }
        NotificationSender sender = senderFor(channel.getKind());
        if (sender == null) {
            delivery.recordDead("No sender registered for channel kind " + channel.getKind());
            deliveries.save(delivery);
            return;
        }

        String secret;
        try {
            secret = channel.getSecretCt() == null
                    ? ""
                    : vault.decrypt(
                            channel.getId() + "|" + channel.getKind(), channel.getSecretCt(), channel.getSecretNonce());
        } catch (RuntimeException e) {
            delivery.recordDead("Failed to decrypt channel secret: " + e.getMessage());
            deliveries.save(delivery);
            return;
        }

        NotificationSender.Result result =
                sender.send(delivery.getSeq(), channel.getConfig(), secret, delivery.getPayload());
        Instant now = Instant.now();
        if (result.success()) {
            delivery.recordSuccess(now);
        } else if (result.permanent()) {
            delivery.recordDead(result.error());
        } else {
            Duration delay = result.retryAfter() != null
                    ? result.retryAfter()
                    : AlertBackoff.delayFor(
                            delivery.getAttempts() + 1,
                            settings.duration(AlertingSettings.INITIAL_BACKOFF),
                            settings.duration(AlertingSettings.MAX_BACKOFF));
            delivery.recordFailure(now, result.error(), delay, settings.intValue(AlertingSettings.MAX_ATTEMPTS));
            log.warn(
                    "Notification delivery {} to channel {} failed (attempt {}): {}",
                    delivery.getSeq(),
                    channel.getName(),
                    delivery.getAttempts(),
                    result.error());
        }
        deliveries.save(delivery);
    }

    private NotificationSender senderFor(String kind) {
        return senders.stream().filter(s -> s.kind().equals(kind)).findFirst().orElse(null);
    }
}
