package io.github.sudoitir.artemisstudio.broker.core;

import io.github.sudoitir.artemisstudio.domain.topology.NodeEndpoint;
import jakarta.jms.Connection;
import jakarta.jms.JMSException;
import jakarta.jms.Session;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.springframework.stereotype.Component;

/**
 * Opens and immediately closes one Core subscription, to answer "would the
 * notification channel work?" before a cluster is registered.
 *
 * <p>{@code checkConnection} used to pass {@link SubscriptionVerdict.NotAttempted}
 * to the capability probe, because the real verdict is a cached property of a
 * running {@link CoreSubscriptionManager} and there is no cluster to look up yet.
 * The result was that "Check connection" reported nothing at all about the Core
 * channel: an operator who entered a management account that the broker reserves as
 * its {@code <cluster-user>} — or simply the same wrong password twice — saw a
 * green check, registered, and only then found the subscription failing with
 * {@code AMQ229099}, with no path back to fixing it short of re-registering.
 *
 * <p>This does the smallest thing that produces a real verdict: connect, subscribe
 * to {@code activemq.notifications}, close. It never starts a drain thread and never
 * emits an event, so it cannot be confused with a live subscription. One node is
 * enough — the credentials and the address permissions are cluster-wide.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CoreSubscriptionCheck {

    private static final String NOTIFICATIONS_ADDRESS = "activemq.notifications";

    private final CoreConnectionFactory factory;

    /**
     * @param endpoints the discovered nodes, in preview order
     * @param settings the Core credentials to try — for an unregistered cluster these
     *     come straight from the request body, never from the vault
     */
    public SubscriptionVerdict probe(List<NodeEndpoint> endpoints, CoreConnectionSettings settings) {
        NodeEndpoint target = endpoints.stream()
                .filter(e -> e.active() && e.coreUrl() != null)
                .findFirst()
                .orElse(null);
        if (target == null) {
            // Not a failure: discovery may simply not have learned a Core URL yet.
            // Reporting it as one would tell the operator to fix something that is fine.
            return new SubscriptionVerdict.NotAttempted();
        }

        ActiveMQConnectionFactory connectionFactory = factory.build(settings, CoreUrl.dialable(target.coreUrl()));
        try (Connection connection = connectionFactory.getUser() != null
                ? connectionFactory.createConnection(connectionFactory.getUser(), connectionFactory.getPassword())
                : connectionFactory.createConnection()) {
            connection.start();
            try (Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
                // Creating the consumer is the part that needs consume AND
                // createNonDurableQueue on activemq.notifications, so it is what
                // actually distinguishes "authenticated" from "permitted".
                session.createConsumer(session.createTopic(NOTIFICATIONS_ADDRESS))
                        .close();
            }
            return new SubscriptionVerdict.Connected(1, java.time.Instant.now());
        } catch (JMSException e) {
            return new SubscriptionVerdict.Failed(CoreEventClient.classify(e), e.getMessage());
        } catch (RuntimeException e) {
            log.debug("Core subscription pre-check failed for {}: {}", target.coreUrl(), e.toString());
            return new SubscriptionVerdict.Failed(CoreEventClient.Kind.UNKNOWN, e.getMessage());
        } finally {
            connectionFactory.close();
        }
    }
}
