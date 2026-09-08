package io.github.sudoitir.artemisstudio.broker.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.broker.JolokiaRequest;
import io.github.sudoitir.artemisstudio.broker.JolokiaResponse;
import io.github.sudoitir.artemisstudio.broker.QueueLifecycleOperations;
import io.github.sudoitir.artemisstudio.broker.routing.DivertOperations;
import io.github.sudoitir.artemisstudio.broker.routing.DivertRow;
import io.github.sudoitir.artemisstudio.config.ArtemisStudioProperties;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * The two refusals (ADR-0062 D6). Both exist because the alternative is a tap that
 * installs cleanly and is wrong in a way nothing else in the product would reveal.
 */
class CaptureTapTest {

    private static final String BROKER = "org.apache.activemq.artemis:broker=\"primary\"";

    private DivertOperations diverts;
    private QueueLifecycleOperations queues;
    private JolokiaBrokerClient client;
    private CaptureTap tap;

    private final CaptureTap.Spec spec = new CaptureTap.Spec(UUID.randomUUID(), "ORDER.IN", 10_000L, null);

    @BeforeEach
    void setUp() {
        diverts = mock(DivertOperations.class);
        queues = mock(QueueLifecycleOperations.class);
        client = mock(JolokiaBrokerClient.class);
        deployed.clear();
        when(diverts.listDiverts(any(), any(), any())).thenAnswer(invocation -> List.copyOf(deployed));
        when(client.resolveBrokerObjectName()).thenReturn(BROKER);
        when(client.single(any(JolokiaRequest.class))).thenReturn(ok());
        tap = new CaptureTap(diverts, queues, properties(), new ObjectMapper());
    }

    @Test
    void refusesWhenAnExclusiveDivertWouldShadowTheCaptureDivert() {
        deployed.add(divert("legacy.router", "ORDER.IN", "ORDER.ROUTED", true));

        assertThatThrownBy(() -> tap.install(client, "abc12345", spec))
                .isInstanceOf(CaptureRefusedException.class)
                .hasMessageContaining("legacy.router")
                .hasMessageContaining("exclusive")
                // The refusal names where the traffic actually goes, because that is
                // the address the operator can capture instead.
                .hasMessageContaining("ORDER.ROUTED");

        verify(diverts, never()).createDivert(any(), any(), any());
        verify(queues, never()).createQueue(any(), any(), any());
    }

    @Test
    void anExclusiveDivertOnAnotherAddressDoesNotShadowThisOne() {
        deployed.add(divert("elsewhere", "PAYMENT.IN", "PAYMENT.ROUTED", true));
        installOnCreate();

        String name = tap.install(client, "abc12345", spec);

        assertThat(name).isEqualTo("artemis-studio.capture.abc12345.ORDER.IN." + spec.subscriptionId());
        verify(diverts).createDivert(any(), any(), any());
    }

    @Test
    void refusesWhenTheCaptureQueueCannotBeRestricted() {
        when(client.single(any(JolokiaRequest.class))).thenAnswer(invocation -> {
            JolokiaRequest request = invocation.getArgument(0);
            return String.valueOf(request.operation()).startsWith("addSecuritySettings")
                    ? refused("AMQ229032: unauthorized")
                    : ok();
        });

        assertThatThrownBy(() -> tap.install(client, "abc12345", spec))
                .isInstanceOf(CaptureRefusedException.class)
                .hasMessageContaining("second copy")
                .extracting(e -> ((CaptureRefusedException) e).getBrokerXml())
                .asString()
                // The refusal carries the configuration that would grant it, not just
                // the fact that it was refused (non-negotiable #5).
                .contains("security-setting")
                .contains("artemis-studio.capture.#");

        verify(queues, never()).createQueue(any(), any(), any());
        verify(diverts, never()).createDivert(any(), any(), any());
    }

    @Test
    void installBuildsTheTapInDependencyOrder() {
        installOnCreate();

        tap.install(client, "abc12345", spec);

        var order = org.mockito.Mockito.inOrder(queues, diverts);
        order.verify(queues).createAddress(any(), any(), any(), any());
        order.verify(queues).createQueue(any(), any(), any());
        order.verify(diverts).createDivert(any(), any(), any());
    }

    @Test
    void refusesWhenTheBrokerAcceptsTheDivertButDoesNotDeployIt() {
        // Artemis answers 200 and writes AMQ222006 to its own log when a divert's name
        // collides with an existing binding. An install that trusted the status code
        // would report a tap that captures nothing.
        when(diverts.listDiverts(any(), any(), any())).thenReturn(List.of());

        assertThatThrownBy(() -> tap.install(client, "abc12345", spec))
                .isInstanceOf(CaptureRefusedException.class)
                .hasMessageContaining("did not deploy");
    }

    // ---- fixtures --------------------------------------------------------

    /** The broker's divert list, as the tap sees it — mutated by a successful create. */
    private final List<DivertRow> deployed = new java.util.ArrayList<>();

    /** Make {@code listDiverts} answer the way a broker that actually deployed it would. */
    private void installOnCreate() {
        org.mockito.Mockito.doAnswer(invocation -> {
                    Map<String, Object> config = invocation.getArgument(2);
                    deployed.add(divert(String.valueOf(config.get("name")), spec.address(), "x", false));
                    return null;
                })
                .when(diverts)
                .createDivert(any(), any(), any());
    }

    private static ArtemisStudioProperties properties() {
        return new ArtemisStudioProperties(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new ArtemisStudioProperties.Capture("amq", Duration.ofSeconds(30), Duration.ofHours(24)),
                null);
    }

    private static DivertRow divert(String name, String address, String forwarding, boolean exclusive) {
        return new DivertRow(null, null, name, name, address, forwarding, null, "ANYCAST", null, exclusive, false);
    }

    private static JolokiaResponse ok() {
        return new JolokiaResponse(200, null, null, null, null);
    }

    private static JolokiaResponse refused(String error) {
        return new JolokiaResponse(403, null, error, "java.lang.SecurityException", null);
    }
}
