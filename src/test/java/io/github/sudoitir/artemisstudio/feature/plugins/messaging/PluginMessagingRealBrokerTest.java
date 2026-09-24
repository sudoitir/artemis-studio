package io.github.sudoitir.artemisstudio.feature.plugins.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.artemisstudio.feature.plugins.messaging.internal.PluginMessagingReconciler;
import io.github.sudoitir.artemisstudio.feature.queues.DivertOperations;
import io.github.sudoitir.artemisstudio.feature.queues.QueueLifecycleOperations;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginPurged;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.descriptor.PluginDescriptor;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.descriptor.PluginDescriptorParser;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.runtime.PluginRuntime;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.runtime.PluginRuntimeFactory;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.runtime.PluginRuntimeRegistry;
import io.github.sudoitir.artemisstudio.kernel.plugin.support.PluginJarBuilder;
import io.github.sudoitir.artemisstudio.kernel.security.Grant;
import io.github.sudoitir.artemisstudio.kernel.security.ScopeIds;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RolePermissionEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RolePermissionRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.RoleRepository;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.UserRoleEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.UserRoleRepository;
import io.github.sudoitir.artemisstudio.platform.broker.Attempt;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerConnections;
import io.github.sudoitir.artemisstudio.platform.broker.JolokiaBrokerClient;
import io.github.sudoitir.artemisstudio.platform.broker.JolokiaRequest;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterDirectory;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterNode;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterService;
import io.github.sudoitir.artemisstudio.platform.clusters.internal.persistence.ClusterRepository;
import io.github.sudoitir.artemisstudio.platform.clusters.web.ClusterRequests.NodeOverrideRequest;
import io.github.sudoitir.artemisstudio.platform.clusters.web.ClusterRequests.RegisterClusterRequest;
import io.github.sudoitir.artemisstudio.platform.clusters.web.ClusterViews.ClusterDetail;
import io.github.sudoitir.artemisstudio.support.AdminAuthenticationExtension;
import io.github.sudoitir.artemisstudio.support.ArtemisIntegrationTest;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import jakarta.jms.Connection;
import jakarta.jms.DeliveryMode;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * Plugin messaging against a real broker (ADR-0111): a fixture plugin with a message handler is
 * activated for real, registers taps and consumers through its own {@link PluginMessaging}, and
 * everything the plugin-messaging spec promises is checked on the broker itself.
 *
 * <p>The scheduled pass is pushed out of reach; each test drives the reconciler itself.
 */
@ExtendWith(AdminAuthenticationExtension.class)
@TestPropertySource(
        properties = {
            "artemis-studio.capture.broker-role=amq",
            "artemis-studio.plugins.messaging.reconcile-interval=1h",
            "artemis-studio.plugins.messaging.tap-ring-size=5"
        })
class PluginMessagingRealBrokerTest extends PostgresIntegrationTest {

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    PluginRuntimeFactory runtimeFactory;

    @Autowired
    PluginRuntimeRegistry runtimes;

    @Autowired
    PluginDescriptorParser descriptorParser;

    @Autowired
    PluginMessagingReconciler reconciler;

    @Autowired
    ClusterService clusterService;

    @Autowired
    ClusterDirectory directory;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    BrokerConnections connections;

    @Autowired
    QueueLifecycleOperations queueOps;

    @Autowired
    DivertOperations divertOps;

    @Autowired
    AppUserRepository users;

    @Autowired
    RoleRepository roles;

    @Autowired
    RolePermissionRepository rolePermissions;

    @Autowired
    UserRoleRepository userRoles;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    TransactionTemplate tx;

    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private final List<PluginRuntime> active = new ArrayList<>();
    private final List<String> queues = new ArrayList<>();

    private UUID clusterId;
    private JolokiaBrokerClient client;
    private String broker;
    private UUID operator;
    private Connection jms;

    @BeforeEach
    void setUp() throws Exception {
        var attempt = clusterService.register(new RegisterClusterRequest(
                List.of(ArtemisIntegrationTest.jolokiaUrl()),
                "plugin-messaging-" + run,
                null,
                new RegisterClusterRequest.Credentials(
                        ArtemisIntegrationTest.BROKER_USER, ArtemisIntegrationTest.BROKER_PASSWORD),
                null,
                null));
        if (!(attempt instanceof Attempt.Ok<ClusterDetail> ok)) {
            throw new IllegalStateException("could not register the container broker: " + attempt);
        }
        clusterId = ok.value().id();
        ClusterNode node = directory.nodes(clusterId).getFirst();
        clusterService.overrideNodeUrl(
                clusterId, node.getId(), new NodeOverrideRequest(null, ArtemisIntegrationTest.coreUrl()));
        client = connections.forCluster(clusterId, ArtemisIntegrationTest.jolokiaUrl());
        broker = client.resolveBrokerObjectName();
        operator = user(Set.of("message:read", "message:send", "queue:purge"));

        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(ArtemisIntegrationTest.coreUrl());
        jms = factory.createConnection(ArtemisIntegrationTest.BROKER_USER, ArtemisIntegrationTest.BROKER_PASSWORD);
        jms.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        for (PluginRuntime runtime : active) {
            quietly(runtime::close);
            runtimes.remove(runtime.id());
            events.publishEvent(new PluginPurged(runtime.id()));
            MessagingProbe.forget(runtime.id());
        }
        reconciler.reconcileNow(clusterId);
        for (String queue : queues) {
            quietly(() -> queueOps.destroyQueue(client, broker, queue, true));
            quietly(() -> queueOps.deleteAddress(client, broker, queue));
        }
        quietly(jms::close);
        clusters.deleteById(clusterId);
    }

    // ---- tap -----------------------------------------------------------------

    @Test
    void aTapCopiesEveryMessageAndLeavesTheExistingConsumerUntouched() throws Exception {
        String queue = queue("TAP");
        String plugin = activate("tap");
        MessageRegistration reg = messaging(plugin)
                .register(new RegistrationSpec("orders", clusterId, queue, RegistrationMode.TAP, operator));
        assertThat(reg.state()).isEqualTo(RegistrationState.ACTIVE);
        assertThat(tapQueues()).hasSize(1);

        Session session = jms.createSession(false, Session.AUTO_ACKNOWLEDGE);
        MessageConsumer application = session.createConsumer(session.createQueue(queue));
        send(queue, 3, 16);

        for (int i = 0; i < 3; i++) {
            assertThat(application.receive(10_000))
                    .as("application message %d", i)
                    .isNotNull();
        }
        for (int i = 0; i < 3; i++) {
            PluginMessage copy = MessagingProbe.inbox(plugin).poll(10, TimeUnit.SECONDS);
            assertThat(copy).as("copy %d", i).isNotNull();
            assertThat(copy.properties()).containsEntry("type", "A");
            assertThat(copy.registrationKey()).isEqualTo("orders");
        }
        assertThat(application.receive(500)).isNull();
    }

    @Test
    void aPluginThatStopsKeepingUpNeverBlocksProducersAndItsDropsAreCounted() throws Exception {
        String queue = queue("SLOW");
        String plugin = activate("slow");
        MessagingProbe.GATE.put(plugin, new Semaphore(0));
        messaging(plugin).register(new RegistrationSpec("slow", clusterId, queue, RegistrationMode.TAP, operator));

        long started = System.nanoTime();
        send(queue, 100, 64 * 1024);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(30));
        assertThat(queueCount(queue)).isEqualTo(100);

        reconciler.reconcileNow(clusterId);
        MessageRegistration reg = messaging(plugin).registration("slow").orElseThrow();
        assertThat(reg.droppedCopies()).isNotNull().isGreaterThan(0L);
    }

    // ---- consume -------------------------------------------------------------

    @Test
    void aRejectedMessageIsRedeliveredAndAnAcceptedOneLeavesTheQueue() throws Exception {
        String queue = queue("CONSUME");
        String plugin = activate("consume");
        MessagingProbe.ANSWER.put(plugin, Disposition.REJECT);
        messaging(plugin).register(new RegistrationSpec("work", clusterId, queue, RegistrationMode.CONSUME, operator));

        send(queue, 1, 16);
        PluginMessage first = MessagingProbe.inbox(plugin).poll(10, TimeUnit.SECONDS);
        assertThat(first).isNotNull();
        assertThat(first.deliveryCount()).isEqualTo(1);

        MessagingProbe.ANSWER.put(plugin, Disposition.ACCEPT);
        PluginMessage again = null;
        for (int i = 0; i < 20 && (again == null || again.deliveryCount() < 2); i++) {
            again = MessagingProbe.inbox(plugin).poll(10, TimeUnit.SECONDS);
        }
        assertThat(again).isNotNull();
        assertThat(again.deliveryCount()).isGreaterThanOrEqualTo(2);
        assertThat(again.headers().get("messageId")).isEqualTo(first.headers().get("messageId"));
        awaitQueueCount(queue, 0);
    }

    @Test
    void messagesAPluginHadNotSettledWhenItStoppedAreDeliveredAgain() throws Exception {
        String queue = queue("STOP");
        String plugin = activate("stop");
        Semaphore gate = new Semaphore(0);
        MessagingProbe.GATE.put(plugin, gate);
        messaging(plugin).register(new RegistrationSpec("work", clusterId, queue, RegistrationMode.CONSUME, operator));
        send(queue, 2, 16);
        assertThat(MessagingProbe.inbox(plugin).poll(10, TimeUnit.SECONDS)).isNotNull();

        // Stop the plugin while it holds the first message and the second is still unsettled.
        PluginRuntime runtime = active.getFirst();
        Thread stopping = Thread.ofVirtual().start(runtime::close);
        Thread.sleep(200);
        gate.release(1);
        stopping.join(Duration.ofSeconds(30));
        runtimes.remove(plugin);
        active.remove(runtime);
        events.publishEvent(new PluginPurged(plugin));
        MessagingProbe.forget(plugin);

        // The one it accepted is gone; the one it never answered is back on the queue.
        awaitQueueCount(queue, 1);

        String again = activate("stop2");
        messaging(again).register(new RegistrationSpec("work", clusterId, queue, RegistrationMode.CONSUME, operator));
        PluginMessage redelivered = MessagingProbe.inbox(again).poll(10, TimeUnit.SECONDS);
        assertThat(redelivered).isNotNull();
        awaitQueueCount(queue, 0);
    }

    // ---- send ----------------------------------------------------------------

    @Test
    void aSentMessageArrivesWithItsBodyAndHeaders() throws Exception {
        String queue = queue("SEND");
        String plugin = activate("send");
        Session session = jms.createSession(false, Session.AUTO_ACKNOWLEDGE);
        MessageConsumer consumer = session.createConsumer(session.createQueue(queue));

        messaging(plugin)
                .send(new OutboundMessage(
                        clusterId,
                        queue,
                        "{\"amount\":5000}".getBytes(StandardCharsets.UTF_8),
                        true,
                        Map.of("type", "A"),
                        Map.of("attempt", 3),
                        true,
                        operator));

        Message received = consumer.receive(10_000);
        assertThat(received).isInstanceOf(TextMessage.class);
        assertThat(((TextMessage) received).getText()).isEqualTo("{\"amount\":5000}");
        assertThat(received.getStringProperty("type")).isEqualTo("A");
        assertThat(received.getIntProperty("attempt")).isEqualTo(3);
    }

    @Test
    void sendingNeedsMessageSendAndNeverReachesStudiosOwnAddresses() throws Exception {
        String queue = queue("SENDNO");
        String plugin = activate("sendno");
        UUID reader = user(Set.of("message:read"));
        assertThatThrownBy(() -> messaging(plugin)
                        .send(new OutboundMessage(
                                clusterId, queue, new byte[0], true, Map.of(), Map.of(), false, reader)))
                .isInstanceOf(RegistrationRefusedException.class)
                .hasMessageContaining("message:send");
        assertThatThrownBy(() -> messaging(plugin)
                        .send(new OutboundMessage(
                                clusterId,
                                "artemis-studio.capture.x",
                                new byte[0],
                                true,
                                Map.of(),
                                Map.of(),
                                false,
                                operator)))
                .isInstanceOf(RegistrationRefusedException.class)
                .hasMessageContaining("reserved");
    }

    // ---- lifecycle and permissions ------------------------------------------

    @Test
    void aStoppedPluginLeavesNothingOnTheBroker() throws Exception {
        String queue = queue("GONE");
        String plugin = activate("gone");
        messaging(plugin).register(new RegistrationSpec("orders", clusterId, queue, RegistrationMode.TAP, operator));
        assertThat(tapQueues()).hasSize(1);
        PluginMessaging handle = messaging(plugin);

        PluginRuntime runtime = active.getFirst();
        runtime.close();
        runtimes.remove(plugin);
        reconciler.reconcileNow(clusterId);

        assertThat(tapQueues()).isEmpty();
        assertThat(tapDiverts()).isEmpty();
        assertThat(handle.registration("orders").orElseThrow().state()).isEqualTo(RegistrationState.INACTIVE);

        events.publishEvent(new PluginPurged(plugin));
        assertThat(handle.registrations()).isEmpty();
        active.remove(runtime);
        MessagingProbe.forget(plugin);
    }

    @Test
    void unregisteringRemovesTheTapAtOnce() throws Exception {
        String queue = queue("UNREG");
        String plugin = activate("unreg");
        messaging(plugin).register(new RegistrationSpec("orders", clusterId, queue, RegistrationMode.TAP, operator));
        assertThat(tapQueues()).hasSize(1);
        assertThat(messaging(plugin).unregister("orders")).isTrue();
        assertThat(tapQueues()).isEmpty();
        assertThat(messaging(plugin).registrations()).isEmpty();
    }

    @Test
    void aRevokedGrantSuspendsTheRegistrationAndARestoredOneResumesIt() throws Exception {
        String queue = queue("REVOKE");
        String plugin = activate("revoke");
        UUID reader = user(Set.of("message:read"));
        messaging(plugin).register(new RegistrationSpec("orders", clusterId, queue, RegistrationMode.TAP, reader));
        assertThat(tapQueues()).hasSize(1);

        List<UserRoleEntity> grants = tx.execute(s -> userRoles.findByIdUserId(reader));
        userRoles.deleteAll(grants);
        reconciler.reconcileNow(clusterId);

        MessageRegistration suspended = messaging(plugin).registration("orders").orElseThrow();
        assertThat(suspended.state()).isEqualTo(RegistrationState.SUSPENDED);
        assertThat(suspended.detail()).contains("message:read").contains(clusterId.toString());
        assertThat(tapQueues()).isEmpty();

        userRoles.saveAll(grants.stream()
                .map(g -> new UserRoleEntity(
                        reader,
                        g.getId().getRoleId(),
                        g.getId().getScopeType(),
                        g.getId().getScopeId()))
                .toList());
        reconciler.reconcileNow(clusterId);
        assertThat(messaging(plugin).registration("orders").orElseThrow().state())
                .isEqualTo(RegistrationState.ACTIVE);
    }

    @Test
    void aRegistrationIsRefusedWithoutThePermissionsItsModeNeeds() throws Exception {
        String queue = queue("DENY");
        String plugin = activate("deny");
        UUID nobody = user(Set.of());
        UUID reader = user(Set.of("message:read"));
        assertThatThrownBy(() -> messaging(plugin)
                        .register(new RegistrationSpec("x", clusterId, queue, RegistrationMode.TAP, nobody)))
                .isInstanceOf(RegistrationRefusedException.class)
                .hasMessageContaining("message:read");
        assertThatThrownBy(() -> messaging(plugin)
                        .register(new RegistrationSpec("x", clusterId, queue, RegistrationMode.CONSUME, reader)))
                .isInstanceOf(RegistrationRefusedException.class)
                .hasMessageContaining("queue:purge");
        assertThatThrownBy(() -> messaging(plugin)
                        .register(new RegistrationSpec(
                                "x", clusterId, "artemis-studio.capture.abc", RegistrationMode.TAP, operator)))
                .isInstanceOf(RegistrationRefusedException.class)
                .hasMessageContaining("reserved");
        assertThat(messaging(plugin).registrations()).isEmpty();
    }

    @Test
    void pluginsSeeAndReceiveOnlyTheirOwnRegistrations() throws Exception {
        String queueA = queue("ISOA");
        String queueB = queue("ISOB");
        String a = activate("isoa");
        String b = activate("isob");
        messaging(a).register(new RegistrationSpec("same", clusterId, queueA, RegistrationMode.TAP, operator));
        messaging(b).register(new RegistrationSpec("same", clusterId, queueB, RegistrationMode.TAP, operator));

        assertThat(messaging(a).registrations())
                .extracting(MessageRegistration::queue)
                .containsExactly(queueA);
        assertThat(messaging(b).registrations())
                .extracting(MessageRegistration::queue)
                .containsExactly(queueB);

        send(queueA, 1, 16);
        assertThat(MessagingProbe.inbox(a).poll(10, TimeUnit.SECONDS)).isNotNull();
        assertThat(MessagingProbe.inbox(b).poll(1, TimeUnit.SECONDS)).isNull();

        messaging(b).unregister("same");
        assertThat(messaging(a).registration("same")).isPresent();
    }

    // ---- fixture -------------------------------------------------------------

    private String activate(String name) throws Exception {
        String id = "acme-msg" + name + "-" + Math.abs(new SecureRandom().nextInt(1_000_000));
        String pkg = "com.acme.msg" + name + Math.abs(id.hashCode());
        Path jar = new PluginJarBuilder(id)
                .descriptorField("basePackage", pkg)
                .descriptorField("configuration", pkg + ".PluginConfig")
                .changelog("""
                        <?xml version="1.0" encoding="UTF-8"?>
                        <databaseChangeLog
                                xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                                    http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
                        </databaseChangeLog>
                        """)
                .source(pkg + ".Handler", """
                        package %s;
                        import io.github.sudoitir.artemisstudio.feature.plugins.messaging.*;
                        import org.springframework.stereotype.Component;
                        @Component
                        public class Handler implements PluginMessageHandler {
                            public Disposition onMessage(PluginMessage m) {
                                return MessagingProbe.received("%s", m);
                            }
                        }
                        """.formatted(pkg, id))
                .source(pkg + ".Holder", """
                        package %s;
                        import io.github.sudoitir.artemisstudio.feature.plugins.messaging.*;
                        import io.github.sudoitir.artemisstudio.kernel.security.PluginSecrets;
                        import org.springframework.stereotype.Component;
                        @Component
                        public class Holder {
                            public Holder(PluginMessaging messaging, PluginSecrets secrets) {
                                MessagingProbe.MESSAGING.put("%s", messaging);
                                MessagingProbe.SECRETS.put("%s", secrets);
                            }
                        }
                        """.formatted(pkg, id, id))
                .build();
        PluginDescriptor descriptor;
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            descriptor = descriptorParser.parse(
                    jarFile.getInputStream(jarFile.getEntry("META-INF/artemis-studio/plugin.json"))
                            .readAllBytes());
        }
        PluginRuntime runtime = runtimeFactory.activate(descriptor, jar, webContext.getServletContext());
        runtimes.set(id, new PluginRuntimeRegistry.Active(runtime));
        active.add(runtime);
        return id;
    }

    private static PluginMessaging messaging(String pluginId) {
        return MessagingProbe.MESSAGING.get(pluginId);
    }

    private UUID user(Set<String> permissions) {
        String username = "pm-" + UUID.randomUUID();
        AppUserEntity user = AppUserEntity.local(username, username + "@example.test", "{noop}unused");
        user.setMustChangePassword(false);
        users.save(user);
        RoleEntity role = roles.save(new RoleEntity("role-" + UUID.randomUUID(), false));
        for (String action : permissions) {
            rolePermissions.save(new RolePermissionEntity(role.getId(), action));
        }
        userRoles.save(new UserRoleEntity(user.getId(), role.getId(), Grant.ScopeType.GLOBAL.name(), ScopeIds.GLOBAL));
        return user.getId();
    }

    private String queue(String suffix) {
        String name = "PM." + suffix + "." + run;
        queueOps.createAddress(client, broker, name, "ANYCAST");
        queueOps.createQueue(
                client, broker, Map.of("name", name, "address", name, "routing-type", "ANYCAST", "durable", true));
        queues.add(name);
        return name;
    }

    private void send(String queue, int count, int bytes) throws Exception {
        try (Session session = jms.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
            MessageProducer producer = session.createProducer(session.createQueue(queue));
            producer.setDeliveryMode(DeliveryMode.PERSISTENT);
            String body = "x".repeat(bytes);
            for (int i = 0; i < count; i++) {
                TextMessage m = session.createTextMessage(body);
                m.setStringProperty("type", "A");
                producer.send(m);
            }
        }
    }

    private long queueCount(String queue) {
        JsonNode value = client.single(JolokiaRequest.read(
                        io.github.sudoitir.artemisstudio.platform.broker.BrokerMBeans.queue(
                                broker, queue, queue, "anycast"),
                        "MessageCount"))
                .attribute("MessageCount");
        return value == null ? -1 : value.asLong();
    }

    private void awaitQueueCount(String queue, long expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (queueCount(queue) != expected && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        assertThat(queueCount(queue)).isEqualTo(expected);
    }

    private List<String> tapQueues() {
        JsonNode value =
                client.single(JolokiaRequest.read(broker, "QueueNames")).attribute("QueueNames");
        return value.valueStream()
                .map(JsonNode::asString)
                .filter(n -> n.startsWith(DivertOperations.PLUGIN_TAP_PREFIX))
                .toList();
    }

    private List<String> tapDiverts() {
        return divertOps.listDiverts(client, null, null).stream()
                .map(d -> d.uniqueName())
                .filter(n -> n != null && n.startsWith(DivertOperations.PLUGIN_TAP_PREFIX))
                .toList();
    }

    private static void quietly(ThrowingRunnable action) {
        try {
            action.run();
        } catch (Exception ignored) {
            // already gone, or never created
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
