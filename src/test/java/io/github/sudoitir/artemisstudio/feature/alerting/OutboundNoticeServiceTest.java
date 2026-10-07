package io.github.sudoitir.artemisstudio.feature.alerting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.feature.alerting.NoticeMessage.Severity;
import io.github.sudoitir.artemisstudio.feature.alerting.internal.persistence.AlertDeliveryEntity;
import io.github.sudoitir.artemisstudio.feature.alerting.internal.persistence.AlertDeliveryRepository;
import io.github.sudoitir.artemisstudio.feature.alerting.internal.persistence.NotificationChannelRepository;
import io.github.sudoitir.artemisstudio.kernel.core.StudioProperties;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

class OutboundNoticeServiceTest {

    private final AlertDeliveryRepository deliveries = mock(AlertDeliveryRepository.class);
    private final NotificationChannelRepository channels = mock(NotificationChannelRepository.class);
    private final UUID channelId = UUID.randomUUID();

    private String enqueueWith(String publicUrl) {
        when(channels.existsById(channelId)).thenReturn(true);
        var service = new OutboundNoticeService(
                deliveries,
                channels,
                new StudioProperties(publicUrl),
                JsonMapper.builder().build(),
                mock(JdbcTemplate.class));

        service.enqueue("approvals", channelId, new NoticeMessage("t", null, Severity.INFO, null, "/approvals/3"));

        ArgumentCaptor<AlertDeliveryEntity> saved = ArgumentCaptor.forClass(AlertDeliveryEntity.class);
        verify(deliveries).save(saved.capture());
        assertThat(saved.getValue().getSource()).isEqualTo("approvals");
        assertThat(saved.getValue().getKind()).isEqualTo("notice");
        return saved.getValue().getPayload();
    }

    @Test
    void theLinkIsMadeAbsoluteFromThePublicUrl() {
        assertThat(enqueueWith("https://studio.example.com/"))
                .contains("\"url\":\"https://studio.example.com/approvals/3\"");
    }

    @Test
    void withoutAPublicUrlThereIsNoLink() {
        assertThat(enqueueWith("")).contains("\"url\":null");
    }

    @Test
    void theScopedBeanSendsAsItsPlugin() {
        when(channels.existsById(any())).thenReturn(true);
        var service = new OutboundNoticeService(
                deliveries,
                channels,
                new StudioProperties(""),
                JsonMapper.builder().build(),
                mock(JdbcTemplate.class));
        OutboundNotices scoped = (OutboundNotices) service.beansFor("my-plugin").get(OutboundNoticeService.BEAN_NAME);

        scoped.enqueue(channelId, new NoticeMessage("t", null, Severity.INFO, null, null));

        ArgumentCaptor<AlertDeliveryEntity> saved = ArgumentCaptor.forClass(AlertDeliveryEntity.class);
        verify(deliveries).save(saved.capture());
        assertThat(saved.getValue().getSource()).isEqualTo("my-plugin");
    }
}
