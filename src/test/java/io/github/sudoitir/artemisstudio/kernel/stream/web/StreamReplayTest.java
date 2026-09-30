package io.github.sudoitir.artemisstudio.kernel.stream.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import io.github.sudoitir.artemisstudio.feature.events.BrokerEventService;
import io.github.sudoitir.artemisstudio.feature.events.web.EventViews.BrokerEventView;
import io.github.sudoitir.artemisstudio.kernel.replica.BusFrame;
import io.github.sudoitir.artemisstudio.support.AdminAuthenticationExtension;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/**
 * What a reconnecting client reads off the wire, with the real hub: the replay comes first, in order,
 * the live frames that arrived while it ran follow it, and no id is sent twice.
 */
@ExtendWith(AdminAuthenticationExtension.class)
class StreamReplayTest extends PostgresIntegrationTest {

    MockMvc mvc;

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    ApplicationEventPublisher publisher;

    @Autowired
    ObjectMapper mapper;

    @MockitoBean
    BrokerEventService events;

    @BeforeEach
    void setUp() {
        mvc = webAppContextSetup(webContext).build();
    }

    @Test
    void liveFramesThatArriveDuringTheReplayFollowItWithoutRepeatingAReplayedId() throws Exception {
        UUID clusterId = UUID.randomUUID();
        when(events.since(eq(clusterId), eq(10L), anyInt())).thenAnswer(invocation -> {
            // The same events are announced live while the replay query runs, plus a newer one.
            live(clusterId, 12);
            live(clusterId, 13);
            return List.of(view(11), view(12));
        });

        MvcResult result = mvc.perform(get("/api/v1/stream")
                        .param("clusterId", clusterId.toString())
                        .param("topics", "events")
                        .param("lastEventId", "10"))
                .andExpect(request().asyncStarted())
                .andReturn();

        live(clusterId, 14);
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(ids(result)).containsExactly(11L, 12L, 13L, 14L));
    }

    @Test
    void anOlderLiveFrameThanTheClientsLastIdIsNotRepeated() throws Exception {
        UUID clusterId = UUID.randomUUID();
        when(events.since(eq(clusterId), eq(10L), anyInt())).thenAnswer(invocation -> {
            live(clusterId, 9);
            live(clusterId, 10);
            return List.of();
        });

        MvcResult result = mvc.perform(get("/api/v1/stream")
                        .param("clusterId", clusterId.toString())
                        .param("topics", "events")
                        .header("Last-Event-ID", "10"))
                .andExpect(request().asyncStarted())
                .andReturn();

        live(clusterId, 11);
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(ids(result)).containsExactly(11L));
    }

    @Test
    void aCappedReplayEndsWithAResyncBeforeLiveFramesResume() throws Exception {
        UUID clusterId = UUID.randomUUID();
        when(events.since(eq(clusterId), anyLong(), eq(500)))
                .thenReturn(LongStream.rangeClosed(11, 510)
                        .mapToObj(StreamReplayTest::view)
                        .toList());

        MvcResult result = mvc.perform(get("/api/v1/stream")
                        .param("clusterId", clusterId.toString())
                        .param("topics", "events")
                        .param("lastEventId", "10"))
                .andExpect(request().asyncStarted())
                .andReturn();

        live(clusterId, 511);
        await().atMost(Duration.ofSeconds(5))
                .until(() -> result.getResponse().getContentAsString().contains("id:511"));
        String body = result.getResponse().getContentAsString();
        assertThat(body.lastIndexOf("id:510")).isLessThan(body.indexOf("event:resync"));
        assertThat(body.indexOf("event:resync")).isLessThan(body.indexOf("id:511"));
    }

    private void live(UUID clusterId, long seq) {
        publisher.publishEvent(new BusFrame(clusterId, "events", mapper.valueToTree(view(seq)), Long.toString(seq)));
    }

    /** The {@code id:} of every {@code events} frame the client has been sent, in order. */
    private static List<Long> ids(MvcResult result) throws Exception {
        return result.getResponse()
                .getContentAsString()
                .lines()
                .filter(line -> line.startsWith("id:"))
                .map(line -> Long.parseLong(line.substring(3)))
                .toList();
    }

    private static BrokerEventView view(long seq) {
        return new BrokerEventView(
                seq,
                Instant.now(),
                Instant.now(),
                "CONSUMER_CREATED",
                "a",
                "r",
                "c",
                "s",
                "conn",
                "1.2.3.4",
                "u",
                null,
                Map.of());
    }
}
