package io.github.sudoitir.artemisstudio.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import io.github.sudoitir.artemisstudio.sse.SseHub;
import io.github.sudoitir.artemisstudio.sse.Subscriber;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
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
class StreamControllerTest extends PostgresIntegrationTest {

    MockMvc mvc;

    @Autowired
    WebApplicationContext webContext;

    @MockitoBean
    SseHub hub;

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
    void theStreamSetsTheNoProxyBufferingHeader() throws Exception {
        UUID clusterId = UUID.randomUUID();

        MvcResult result = mvc.perform(get("/api/v1/stream").param("clusterId", clusterId.toString()))
                .andExpect(request().asyncStarted())
                .andReturn();

        assertThat(result.getResponse().getHeader("X-Accel-Buffering")).isEqualTo("no");
        verify(hub).register(eq(clusterId), any(Subscriber.class));
    }
}
