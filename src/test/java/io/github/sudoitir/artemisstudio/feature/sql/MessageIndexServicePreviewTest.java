package io.github.sudoitir.artemisstudio.feature.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.feature.sql.internal.persistence.MessageCaptureNodeRepository;
import io.github.sudoitir.artemisstudio.feature.sql.internal.persistence.MessageIndexSubscriptionRepository;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditService;
import io.github.sudoitir.artemisstudio.kernel.security.ActorResolver;
import io.github.sudoitir.artemisstudio.kernel.security.ClusterAccessGuard;
import io.github.sudoitir.artemisstudio.kernel.settings.StudioInstance;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterDirectory;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterNode;
import io.github.sudoitir.artemisstudio.platform.scrape.QueueSnapshots;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.ObjectMapper;

/** The capture dry run resolves what a subscription would do, and does none of it. */
class MessageIndexServicePreviewTest {

    private static final UUID CLUSTER = UUID.randomUUID();

    private MessageIndexSubscriptionRepository subscriptions;
    private AuditService audit;
    private CaptureAddresses addresses;
    private CaptureReconciler reconciler;

    @BeforeEach
    void setUp() {
        subscriptions = mock(MessageIndexSubscriptionRepository.class);
        audit = mock(AuditService.class);
        addresses = mock(CaptureAddresses.class);
        reconciler = mock(CaptureReconciler.class);
        ClusterNode primary = mock(ClusterNode.class);
        when(primary.getName()).thenReturn("primary");
        when(reconciler.servingNodes(CLUSTER)).thenReturn(List.of(primary));
    }

    private MessageIndexService service(String brokerRole) {
        CaptureProperties properties = new CaptureProperties(
                brokerRole, Duration.ofSeconds(30), Duration.ofHours(24), DataSize.ofMegabytes(64));
        StudioInstance instance = mock(StudioInstance.class);
        when(instance.id()).thenReturn("abc12345");
        return new MessageIndexService(
                subscriptions,
                mock(QueueSnapshots.class),
                mock(MessageIndexCapture.class),
                mock(MessageCaptureNodeRepository.class),
                mock(ClusterDirectory.class),
                mock(ClusterAccessGuard.class),
                mock(ActorResolver.class),
                audit,
                mock(JdbcTemplate.class),
                addresses,
                mock(CaptureConsumer.class),
                reconciler,
                new CaptureTap(null, null, properties, new ObjectMapper()),
                properties,
                instance);
    }

    private static MessageIndexService.Spec spec(String pattern, Long ringSize) {
        return new MessageIndexService.Spec(
                pattern, null, 7, null, CaptureMode.CAPTURE, ringSize, null, null, null, null);
    }

    @Test
    void itStatesWhatWouldBeCreatedWhereAndHowBigWithoutSavingOrAuditingAnything() {
        when(addresses.of(any(), any())).thenReturn(Set.of("ORDER.IN"));

        MessageIndexService.Preview preview = service("studio").preview(CLUSTER, spec("ORDER.IN", 50_000L));

        assertThat(preview.addresses()).containsExactly("ORDER.IN");
        assertThat(preview.nodes()).containsExactly("primary");
        assertThat(preview.ringMessages()).isEqualTo(50_000L);
        assertThat(preview.ringBytes()).isEqualTo(DataSize.ofMegabytes(64).toBytes());
        assertThat(preview.brokerObjects())
                .anySatisfy(o -> assertThat(o).startsWith("divert artemis-studio.capture.abc12345.ORDER.IN."))
                .contains("address-setting artemis-studio.capture.abc12345.#");
        assertThat(preview.brokerXml()).contains("<max-size-bytes>");
        assertThat(preview.refusal()).isNull();
        verifyNoInteractions(subscriptions, audit);
    }

    @Test
    void aMissingBrokerRoleIsStatedBeforeAnythingIsArmed() {
        when(addresses.of(any(), any())).thenReturn(Set.of("ORDER.IN"));

        MessageIndexService.Preview preview = service(null).preview(CLUSTER, spec("ORDER.IN", null));

        assertThat(preview.refusal()).contains("artemis-studio.capture.broker-role");
    }

    @Test
    void aPatternThatMatchesNothingSaysSo() {
        when(addresses.of(any(), any())).thenReturn(Set.of());

        MessageIndexService.Preview preview = service("studio").preview(CLUSTER, spec("NOTHING.*", null));

        assertThat(preview.addresses()).isEmpty();
        assertThat(preview.refusal()).contains("matches no address");
    }

    @Test
    void anOutOfRangeBoundIsRefusedNotClamped() {
        assertThatThrownBy(() -> service("studio").preview(CLUSTER, spec("ORDER.IN", 50_000_000L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ringSize must be between 100 and 1000000");
    }

    @Test
    void deletingACaptureStopsRecordingBeforeItsRowsAreDeletedAndSweepsItsTaps() {
        io.github.sudoitir.artemisstudio.feature.sql.internal.persistence.MessageIndexSubscriptionEntity entity =
                new io.github.sudoitir.artemisstudio.feature.sql.internal.persistence.MessageIndexSubscriptionEntity();
        UUID id = UUID.randomUUID();
        entity.setId(id);
        entity.setClusterId(CLUSTER);
        entity.setQueuePattern("ORDER.IN");
        entity.setMode(CaptureMode.CAPTURE);
        entity.setRetentionDays(7);
        when(subscriptions.findById(id)).thenReturn(java.util.Optional.of(entity));
        when(addresses.of(any(), any())).thenReturn(Set.of("ORDER.IN"));
        when(audit.begin(any(), any(), any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(mock(io.github.sudoitir.artemisstudio.kernel.audit.AuditEvent.class));
        CaptureConsumer consumers = mock(CaptureConsumer.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(
                        any(String.class), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class)))
                .thenReturn(new MessageIndexService.Footprint(3, 30, null));
        CaptureProperties properties =
                new CaptureProperties("studio", Duration.ofSeconds(30), Duration.ofHours(24), DataSize.ofMegabytes(64));
        MessageIndexService service = new MessageIndexService(
                subscriptions,
                mock(QueueSnapshots.class),
                mock(MessageIndexCapture.class),
                mock(MessageCaptureNodeRepository.class),
                mock(ClusterDirectory.class),
                mock(ClusterAccessGuard.class),
                mock(ActorResolver.class),
                audit,
                jdbc,
                addresses,
                consumers,
                reconciler,
                new CaptureTap(null, null, properties, new ObjectMapper()),
                properties,
                mock(StudioInstance.class));

        service.delete(CLUSTER, id);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(consumers, jdbc, reconciler);
        order.verify(consumers).stopSubscription(id);
        order.verify(jdbc)
                .update(org.mockito.ArgumentMatchers.startsWith("DELETE FROM message_index"), any(Object[].class));
        // No transaction is active in this test, so the after-commit sweep runs straight away.
        order.verify(reconciler).reconcileNow(CLUSTER);
    }

    @Test
    void turningCaptureOffSweepsItsTapsOnceTheChangeCommits() {
        io.github.sudoitir.artemisstudio.feature.sql.internal.persistence.MessageIndexSubscriptionEntity entity =
                new io.github.sudoitir.artemisstudio.feature.sql.internal.persistence.MessageIndexSubscriptionEntity();
        UUID id = UUID.randomUUID();
        entity.setId(id);
        entity.setClusterId(CLUSTER);
        entity.setQueuePattern("ORDER.IN");
        entity.setMode(CaptureMode.CAPTURE);
        entity.setEnabled(true);
        entity.setRetentionDays(7);
        when(subscriptions.findById(id)).thenReturn(java.util.Optional.of(entity));
        when(subscriptions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(addresses.of(any(), any())).thenReturn(Set.of());
        when(audit.begin(any(), any(), any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(mock(io.github.sudoitir.artemisstudio.kernel.audit.AuditEvent.class));

        service("studio")
                .update(
                        CLUSTER,
                        id,
                        new MessageIndexService.Spec(null, null, null, false, null, null, null, null, null, null));

        // The divert keeps copying every message into its capture queue until it is removed, and
        // with the drain stopped nothing empties that queue. No transaction is active in this test,
        // so the after-commit sweep runs straight away.
        org.mockito.Mockito.verify(reconciler).reconcileNow(CLUSTER);
    }

    @Test
    void studiosOwnCaptureAddressesCannotBeCaptured() {
        assertThatThrownBy(() -> service("studio").preview(CLUSTER, spec("artemis-studio.capture.#", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be captured");
    }
}
