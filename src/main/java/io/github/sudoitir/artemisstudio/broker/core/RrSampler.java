package io.github.sudoitir.artemisstudio.broker.core;

import io.github.sudoitir.artemisstudio.broker.CoreMessageTransport;
import io.github.sudoitir.artemisstudio.broker.MessageBrowser.BrowsedMessage;
import io.github.sudoitir.artemisstudio.broker.MessageTransport.BrowseResult;
import io.github.sudoitir.artemisstudio.broker.MessageTransport.TransportTarget;
import io.github.sudoitir.artemisstudio.domain.rr.Observation;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeEntity;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeRepository;
import io.github.sudoitir.artemisstudio.persist.RrExpectationEntity;
import io.github.sudoitir.artemisstudio.persist.RrExpectationRepository;
import io.github.sudoitir.artemisstudio.service.RrObservationSink;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
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

    private final RrExpectationRepository expectations;
    private final BrokerNodeRepository nodes;
    private final CoreMessageTransport coreTransport;
    private final ObjectProvider<RrObservationSink> sink;

    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    public void tick() {
        RrObservationSink target = sink.getIfAvailable();
        if (target == null) {
            return;
        }
        for (RrExpectationEntity expectation : expectations.findByEnabledTrue()) {
            try {
                sampleExpectation(expectation, target);
            } catch (RuntimeException e) {
                log.debug("Request-reply sampling failed for {}: {}", expectation.getRequestAddress(), e.getMessage());
            }
        }
    }

    private void sampleExpectation(RrExpectationEntity expectation, RrObservationSink target) {
        UUID clusterId = expectation.getClusterId();
        BrokerNodeEntity node = servingNode(clusterId);
        if (node == null || node.getCoreUrl() == null) {
            return;
        }

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

        if (expectation.getReplyAddress() != null) {
            for (BrowsedMessage m : browse(clusterId, node, expectation.getReplyAddress())) {
                target.accept(new Observation.ReplySeen(
                        clusterId,
                        node.getId(),
                        Instant.now(),
                        expectation.getReplyAddress(),
                        String.valueOf(m.messageId()),
                        correlationOf(expectation, m),
                        m.bodyPreview(),
                        Map.of()));
            }
        }
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

    private BrokerNodeEntity servingNode(UUID clusterId) {
        return nodes.findByClusterIdOrderByNameAsc(clusterId).stream()
                .filter(n -> Boolean.TRUE.equals(n.getActive()) && n.getLastError() == null)
                .filter(n -> n.getCoreUrl() != null)
                .findFirst()
                .orElse(null);
    }
}
