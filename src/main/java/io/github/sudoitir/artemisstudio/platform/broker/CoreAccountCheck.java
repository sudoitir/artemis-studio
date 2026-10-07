package io.github.sudoitir.artemisstudio.platform.broker;

import jakarta.jms.Connection;
import jakarta.jms.JMSException;
import jakarta.jms.JMSSecurityException;
import jakarta.jms.Session;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.springframework.stereotype.Component;

/**
 * Whether one node accepts the Core account: opens one Core connection with it, opens and closes one
 * session, and closes the connection. That is the smallest thing that makes the broker authenticate
 * the account, so a registration or a connection edit can say, node by node, what the Core account
 * does without waiting for the first scrape to find out.
 *
 * <p>Unlike {@link CoreSubscriptionCheck} it asks nothing of the broker's address permissions: it
 * answers "does the broker let this account in", not "may it subscribe to notifications".
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CoreAccountCheck {

    private final CoreConnectionFactory factory;

    /**
     * @param coreUrl the node's Core URL, as stored: a bare {@code host:port} connector or a full URL
     * @param settings the Core account to try, with the cluster's TLS bundle when it has one
     */
    public AccountResult check(String coreUrl, CoreConnectionSettings settings) {
        String dialable = CoreUrl.dialable(coreUrl);
        if (dialable == null) {
            return AccountResult.NOT_TRIED;
        }
        try {
            ActiveMQConnectionFactory connectionFactory = factory.build(settings, dialable);
            try (Connection connection = settings.hasCredentials()
                    ? connectionFactory.createConnection(settings.username(), settings.password())
                    : connectionFactory.createConnection()) {
                try (var _ = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
                    return AccountResult.ACCEPTED;
                }
            } finally {
                connectionFactory.close();
            }
        } catch (JMSException | RuntimeException e) {
            log.debug("Core account check failed for {}: {}", coreUrl, e.toString());
            return resultOf(e);
        }
    }

    /** A refused account is {@code REJECTED}; any other failure to connect says nothing about the account. */
    static AccountResult resultOf(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof JMSSecurityException) {
                return AccountResult.REJECTED;
            }
        }
        return CoreEventClient.classify(failure) == CoreEventClient.Kind.UNAUTHORIZED
                ? AccountResult.REJECTED
                : AccountResult.UNREACHABLE;
    }
}
