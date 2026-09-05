package io.github.sudoitir.artemisstudio.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.broker.notify.NotificationSender;
import io.github.sudoitir.artemisstudio.broker.notify.NotificationSender.Result;
import io.github.sudoitir.artemisstudio.config.ArtemisStudioProperties;
import io.github.sudoitir.artemisstudio.config.ArtemisStudioProperties.Alerting;
import io.github.sudoitir.artemisstudio.persist.AlertDeliveryEntity;
import io.github.sudoitir.artemisstudio.persist.AlertDeliveryRepository;
import io.github.sudoitir.artemisstudio.persist.NotificationChannelEntity;
import io.github.sudoitir.artemisstudio.persist.NotificationChannelRepository;
import io.github.sudoitir.artemisstudio.security.SecretVault;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AlertDispatcherTest {

    @Mock
    AlertDeliveryRepository deliveries;

    @Mock
    NotificationChannelRepository channels;

    @Mock
    SecretVault vault;

    @Mock
    NotificationSender slackSender;

    AlertDispatcher dispatcher;

    private final UUID channelId = UUID.randomUUID();
    private NotificationChannelEntity channel;

    @BeforeEach
    void setUp() {
        when(slackSender.kind()).thenReturn("SLACK");
        ArtemisStudioProperties properties = new ArtemisStudioProperties(
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new Alerting(
                        Duration.ofSeconds(5), 3, Duration.ofSeconds(5), Duration.ofSeconds(1), Duration.ofMinutes(1)),
                null);
        dispatcher = new AlertDispatcher(deliveries, channels, List.of(slackSender), vault, properties);
        channel = new NotificationChannelEntity("ops-slack", "SLACK", "{}", new byte[] {1}, new byte[] {2});
        when(channels.findById(channelId)).thenReturn(java.util.Optional.of(channel));
        when(vault.decrypt(any(), any(), any())).thenReturn("https://hooks.slack.com/services/x");
    }

    private AlertDeliveryEntity pending() {
        AlertDeliveryEntity d = new AlertDeliveryEntity(UUID.randomUUID(), channelId, "{}");
        d.setSeq(1L); // normally assigned by the DB; the sender needs a non-null delivery id
        return d;
    }

    @Test
    void serverErrorBacksOffAndRetries() {
        when(slackSender.send(any(Long.class), any(), any(), any())).thenReturn(Result.retryable("500"));
        when(deliveries.claimDue(20)).thenReturn(List.of(pending()));

        dispatcher.dispatch();

        var captor = org.mockito.ArgumentCaptor.forClass(AlertDeliveryEntity.class);
        org.mockito.Mockito.verify(deliveries).save(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo("PENDING");
        assertThat(captor.getValue().getAttempts()).isEqualTo(1);
        assertThat(captor.getValue().getNextAttemptAt())
                .isAfter(captor.getValue().getCreatedAt());
    }

    @Test
    void permanentFailureGoesDeadWithoutRetry() {
        when(slackSender.send(any(Long.class), any(), any(), any())).thenReturn(Result.permanent("revoked"));
        when(deliveries.claimDue(20)).thenReturn(List.of(pending()));

        dispatcher.dispatch();

        var captor = org.mockito.ArgumentCaptor.forClass(AlertDeliveryEntity.class);
        org.mockito.Mockito.verify(deliveries).save(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo("DEAD");
        assertThat(captor.getValue().getAttempts()).isEqualTo(1);
    }

    @Test
    void rateLimitedResponseHonoursRetryAfter() {
        when(slackSender.send(any(Long.class), any(), any(), any()))
                .thenReturn(Result.retryable("429", Duration.ofSeconds(30)));
        when(deliveries.claimDue(20)).thenReturn(List.of(pending()));

        dispatcher.dispatch();

        var captor = org.mockito.ArgumentCaptor.forClass(AlertDeliveryEntity.class);
        org.mockito.Mockito.verify(deliveries).save(captor.capture());
        Duration untilNext = Duration.between(
                captor.getValue().getCreatedAt(), captor.getValue().getNextAttemptAt());
        assertThat(untilNext).isGreaterThanOrEqualTo(Duration.ofSeconds(29));
    }

    @Test
    void reachingMaxAttemptsEndsDead() {
        AlertDeliveryEntity delivery = pending();
        for (int i = 0; i < 2; i++) {
            delivery.recordFailure(java.time.Instant.now(), "err", Duration.ofMillis(1), 3);
        }
        when(slackSender.send(any(Long.class), any(), any(), any())).thenReturn(Result.retryable("still failing"));
        when(deliveries.claimDue(20)).thenReturn(List.of(delivery));

        dispatcher.dispatch();

        assertThat(delivery.getState()).isEqualTo("DEAD");
        assertThat(delivery.getAttempts()).isEqualTo(3);
    }
}
