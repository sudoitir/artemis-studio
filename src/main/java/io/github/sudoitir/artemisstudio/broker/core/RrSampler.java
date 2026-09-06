package io.github.sudoitir.artemisstudio.broker.core;

import io.github.sudoitir.artemisstudio.broker.CoreMessageTransport;
import io.github.sudoitir.artemisstudio.broker.MessageBrowser.BrowsedMessage;
import io.github.sudoitir.artemisstudio.broker.MessageTransport.BrowseResult;
import io.github.sudoitir.artemisstudio.broker.MessageTransport.TransportTarget;
import io.github.sudoitir.artemisstudio.broker.rr.ReplyAddressResolver;
import io.github.sudoitir.artemisstudio.domain.rr.Observation;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeEntity;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeRepository;
import io.github.sudoitir.artemisstudio.persist.RrExpectationEntity;
import io.github.sudoitir.artemisstudio.persist.RrExpectationRepository;
import io.github.sudoitir.artemisstudio.service.RrObservationSink;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Polls page 1 of every enabled expectation's request (and reply, when
 * configured) address over the pooled Core connection, for message
 * correlation identity notifications cannot carry (design.md D2). Broker
 * friendly by construction: bounded page size, no deep queue walk — a healthy
 * request-reply address is near-empty, so page 1 already is the backlog worth
 * seeing.
 *
 * <p>A separate trigger from {@link io.github.sudoitir.artemisstudio.scheduler.ScrapeScheduler}'s
 * Jolokia tiers on purpose — this is a Core-protocol poll and must not consume
 * the Jolokia {@code NodeCallLimiter} budget.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RrSampler {

    private static final int SAMPLE_PAGE_SIZE = 20;
    private static final Duration FAILURE_LOG_INTERVAL = Duration.ofMinutes(1);

    private final RrExpectationRepository expectations;
    private final BrokerNodeRepository nodes;
    private final CoreMessageTransport coreTransport;
    private final ObjectProvider<RrObservationSink> sink;
    private final ReplyAddressResolver replyAddresses;

    /** Per expectation-and-node, when its last failure was logged. */
    private final Map<String, Instant> lastReported = new ConcurrentHashMap<>();

    /**
     * Scheduled by {@code DynamicSchedules} on {@code rr.sample-interval}. It used to
     * hardcode 5s and ignore the configured value entirely.
     */
    public void tick() {
        RrObservationSink target = sink.getIfAvailable();
        if (target == null) {
            return;
        }
        for (RrExpectationEntity expectation : expectations.findByEnabledTrue()) {
            try {
                sampleExpectation(expectation, target);
            } catch (RuntimeException e) {
                log.warn("Request-reply sampling failed for '{}': {}", expectation.getRequestAddress(), e.toString());
            }
        }
    }

    private void sampleExpectation(RrExpectationEntity expectation, RrObservationSink target) {
        UUID clusterId = expectation.getClusterId();
        List<String> replyTargets =
                replyAddresses.resolve(clusterId, expectation).addresses();

        // Every serving node, not just the first: in a three-primary cluster the
        // request and reply queues on the other two were never read, so the
        // correlation identity only browsing can supply was missing for two thirds
        // of the traffic (design.md, D7). Duplicates across nodes are already
        // handled by recentRequestFlow and uq_rr_flow_request.
        for (BrokerNodeEntity node : servingNodes(clusterId)) {
            // One node failing must not cost the others their tick.
            try {
                sampleNode(expectation, node, replyTargets, target);
            } catch (RuntimeException e) {
                reportFailure(expectation, node, e);
            }
        }
    }

    private void sampleNode(
            RrExpectationEntity expectation,
            BrokerNodeEntity node,
            List<String> replyTargets,
            RrObservationSink target) {
        UUID clusterId = expectation.getClusterId();

        for (BrowsedMessage m : browse(clusterId, node, expectation.getRequestAddress())) {
            target.accept(new Observation.RequestSeen(
                    clusterId,
                    node.getId(),
                    Instant.now(),
                    expectation.getRequestAddress(),
                    String.valueOf(m.messageId()),
                    correlationOf(expectation, m),
                    m.replyTo() != null ? CoreDestinationName.extract(m.replyTo()) : null,
                    m.expiration(),
                    m.bodyPreview(),
                    Map.of()));
        }

        for (String replyAddress : replyTargets) {
            for (BrowsedMessage m : browse(clusterId, node, replyAddress)) {
                target.accept(new Observation.ReplySeen(
                        clusterId,
                        node.getId(),
                        Instant.now(),
                        replyAddress,
                        String.valueOf(m.messageId()),
                        correlationOf(expectation, m),
                        m.bodyPreview(),
                        Map.of()));
            }
        }
    }

    /**
     * A sampling failure used to be swallowed at {@code debug}, so a
     * correctly-configured-looking expectation produced nothing and said nothing.
     * It is now a {@code warn} on first occurrence and at most once a minute after,
     * naming the expectation and the node — loud enough to find, quiet enough that a
     * node down for an hour does not fill the log at the sampling cadence.
     */
    private void reportFailure(RrExpectationEntity expectation, BrokerNodeEntity node, RuntimeException e) {
        String key = expectation.getId() + "|" + node.getId();
        Instant now = Instant.now();
        Instant last = lastReported.get(key);
        if (last != null && last.isAfter(now.minus(FAILURE_LOG_INTERVAL))) {
            return;
        }
        lastReported.put(key, now);
        log.warn(
                "Request-reply sampling failed for '{}' on node {}: {}",
                expectation.getRequestAddress(),
                node.getName(),
                e.toString());
    }

    private static String correlationOf(RrExpectationEntity expectation, BrowsedMessage m) {
        if (expectation.getCorrelationProperty() != null) {
            String v = m.stringProperties().get(expectation.getCorrelationProperty());
            if (v != null) {
                return v;
            }
        }
        return m.correlationId();
    }

    private List<BrowsedMessage> browse(UUID clusterId, BrokerNodeEntity node, String address) {
        TransportTarget target = new TransportTarget(
                clusterId, node.getId(), address, address, "ANYCAST", node.getJolokiaUrl(), node.getCoreUrl());
        BrowseResult result = coreTransport.browse(target, 1, SAMPLE_PAGE_SIZE, null);
        return result.page().messages();
    }

    private List<BrokerNodeEntity> servingNodes(UUID clusterId) {
        return nodes.findByClusterIdOrderByNameAsc(clusterId).stream()
                .filter(n -> Boolean.TRUE.equals(n.getActive()) && n.getLastError() == null)
                .filter(n -> n.getCoreUrl() != null)
                .toList();
    }
}
