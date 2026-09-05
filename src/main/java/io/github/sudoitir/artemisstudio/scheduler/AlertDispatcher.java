package io.github.sudoitir.artemisstudio.scheduler;

import io.github.sudoitir.artemisstudio.broker.notify.NotificationSender;
import io.github.sudoitir.artemisstudio.config.ArtemisStudioProperties;
import io.github.sudoitir.artemisstudio.persist.AlertDeliveryEntity;
import io.github.sudoitir.artemisstudio.persist.AlertDeliveryRepository;
import io.github.sudoitir.artemisstudio.persist.NotificationChannelEntity;
import io.github.sudoitir.artemisstudio.persist.NotificationChannelRepository;
import io.github.sudoitir.artemisstudio.security.SecretVault;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
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
    private final ArtemisStudioProperties properties;

    @Scheduled(
            fixedDelayString = "${artemis-studio.alerting.dispatch-interval:5s}",
            initialDelayString = "${artemis-studio.alerting.dispatch-interval:5s}")
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
                            properties.alerting().initialBackoff(),
                            properties.alerting().maxBackoff());
            delivery.recordFailure(
                    now, result.error(), delay, properties.alerting().maxAttempts());
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
