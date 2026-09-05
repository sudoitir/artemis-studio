package io.github.sudoitir.artemisstudio.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import io.github.sudoitir.artemisstudio.service.BrokerEventService;
import io.github.sudoitir.artemisstudio.sse.SseHub;
import io.github.sudoitir.artemisstudio.sse.Subscriber;
import io.github.sudoitir.artemisstudio.support.AdminAuthenticationExtension;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import io.github.sudoitir.artemisstudio.web.dto.EventViews.BrokerEventView;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;

/**
 * {@code GET /api/v1/stream}: subscriber registration, topic parsing, no-buffering header.
 *
 * <p>The emitter has no timeout (the heartbeat keeps it open), so there is no
 * async result to dispatch — the assertions here are all on what the controller
 * does synchronously before it returns the emitter.
 */
@org.junit.jupiter.api.extension.ExtendWith(AdminAuthenticationExtension.class)
class StreamControllerTest extends PostgresIntegrationTest {

    MockMvc mvc;

    @Autowired
    WebApplicationContext webContext;

    @MockitoBean
    SseHub hub;

    @MockitoBean
    BrokerEventService events;

    @BeforeEach
    void setUp() {
        mvc = webAppContextSetup(webContext).build();
    }

    @Test
    void openingTheStreamRegistersASubscriberForTheRequestedTopics() throws Exception {
        UUID clusterId = UUID.randomUUID();

        mvc.perform(get("/api/v1/stream")
                        .param("clusterId", clusterId.toString())
                        .param("topics", "queues,health"))
                .andExpect(request().asyncStarted());

        ArgumentCaptor<Subscriber> captor = ArgumentCaptor.forClass(Subscriber.class);
        verify(hub).register(eq(clusterId), captor.capture());
        assertThat(captor.getValue().topics()).containsExactlyInAnyOrder("queues", "health");
    }

    @Test
    void theRrTopicIsAccepted() throws Exception {
        UUID clusterId = UUID.randomUUID();

        mvc.perform(get("/api/v1/stream")
                        .param("clusterId", clusterId.toString())
                        .param("topics", "rr"))
                .andExpect(request().asyncStarted());

        ArgumentCaptor<Subscriber> captor = ArgumentCaptor.forClass(Subscriber.class);
        verify(hub).register(eq(clusterId), captor.capture());
        assertThat(captor.getValue().topics()).containsExactly("rr");
    }

    @Test
    void unknownTopicsFallBackToTheFullDefaultSet() throws Exception {
        UUID clusterId = UUID.randomUUID();

        mvc.perform(get("/api/v1/stream")
                        .param("clusterId", clusterId.toString())
                        .param("topics", "bogus"))
                .andExpect(request().asyncStarted());

        ArgumentCaptor<Subscriber> captor = ArgumentCaptor.forClass(Subscriber.class);
        verify(hub).register(eq(clusterId), captor.capture());
        assertThat(captor.getValue().topics()).containsExactlyInAnyOrder("topology", "health", "queues");
    }

    @Test
    void aLastEventIdReplaysTheMissedEventsBeforeLiveDelivery() throws Exception {
        UUID clusterId = UUID.randomUUID();
        when(events.since(eq(clusterId), eq(10L), eq(500))).thenReturn(List.of(view(11L), view(12L)));

        mvc.perform(get("/api/v1/stream")
                        .param("clusterId", clusterId.toString())
                        .param("topics", "events")
                        .header("Last-Event-ID", "10"))
                .andExpect(request().asyncStarted());

        verify(hub, times(2)).sendTo(any(Subscriber.class), eq("events"), any(), any());
    }

    @Test
    void noLastEventIdReplaysNothing() throws Exception {
        UUID clusterId = UUID.randomUUID();

        mvc.perform(get("/api/v1/stream")
                        .param("clusterId", clusterId.toString())
                        .param("topics", "events"))
                .andExpect(request().asyncStarted());

        verify(hub, org.mockito.Mockito.never()).sendTo(any(), any(), any(), any());
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

    @Test
    void theStreamSetsTheNoProxyBufferingHeader() throws Exception {
        UUID clusterId = UUID.randomUUID();

        MvcResult result = mvc.perform(get("/api/v1/stream").param("clusterId", clusterId.toString()))
                .andExpect(request().asyncStarted())
                .andReturn();

        assertThat(result.getResponse().getHeader("X-Accel-Buffering")).isEqualTo("no");
        verify(hub).register(eq(clusterId), any(Subscriber.class));
    }
}
