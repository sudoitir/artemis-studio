package io.github.sudoitir.artemisstudio.feature.alerting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.feature.alerting.NoticeMessage.Severity;
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
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final UUID channelId = UUID.randomUUID();

    private String enqueueWith(String publicUrl) {
        when(channels.existsById(channelId)).thenReturn(true);
        var service = new OutboundNoticeService(
                deliveries,
                channels,
                new StudioProperties(publicUrl),
                JsonMapper.builder().build(),
                jdbc);

        service.enqueue(
                "approvals", channelId, new NoticeMessage("t", null, Severity.INFO, null, "/approvals/3"), "request-3");

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(jdbc)
                .update(
                        contains("INSERT INTO alert_delivery"),
                        eq("notice"),
                        eq("approvals"),
                        eq(channelId),
                        payload.capture(),
                        eq("request-3"));
        return payload.getValue();
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
                jdbc);
        OutboundNotices scoped = (OutboundNotices) service.beansFor("my-plugin").get(OutboundNoticeService.BEAN_NAME);

        scoped.enqueue(channelId, new NoticeMessage("t", null, Severity.INFO, null, null), "e-1");

        verify(jdbc)
                .update(
                        contains("INSERT INTO alert_delivery"),
                        eq("notice"),
                        eq("my-plugin"),
                        eq(channelId),
                        any(),
                        eq("e-1"));
    }
}
