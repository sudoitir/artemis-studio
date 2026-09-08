package io.github.sudoitir.artemisstudio.broker.capture;

import io.github.sudoitir.artemisstudio.broker.core.CoreDestinationName;
import io.github.sudoitir.artemisstudio.broker.rr.ReplyAddressResolver;
import io.github.sudoitir.artemisstudio.domain.rr.Observation;
import io.github.sudoitir.artemisstudio.persist.RrExpectationEntity;
import io.github.sudoitir.artemisstudio.persist.RrExpectationRepository;
import io.github.sudoitir.artemisstudio.service.RrObservationSink;
import io.github.sudoitir.artemisstudio.sql.QueryResult.Row;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Request-reply correlation fed from capture rather than from a browse (ADR-0062).
 *
 * <p>A sampled browse sees page 1 of an address every few seconds, so a request that
 * is answered between two samples is never correlated at all. Capture sees every
 * message the address routed, which is exactly the identity the correlator needs and
 * could not get.
 *
 * <p>No deduplication is added here. The correlator already refuses a second flow for
 * the same request — {@code recentRequestFlow} plus {@code uq_rr_flow_request} — which
 * is what makes it safe for a captured address to also be sampled during the window
 * where a tap is being installed.
 *
 * <p>This does not complete request-reply tracing and must not be described as if it
 * does. A temporary reply queue does not exist until the client creates it, so it
 * cannot be pre-diverted; those flows stay notification-derived, as they were.
 */
@Component
@RequiredArgsConstructor
public class CaptureRrSink implements CaptureBus.Listener {

    private final CaptureBus bus;
    private final RrExpectationRepository expectations;
    private final ReplyAddressResolver replyAddresses;
    private final ObjectProvider<RrObservationSink> sink;

    @PostConstruct
    void subscribe() {
        bus.register(this);
    }

    @Override
    public void captured(CaptureBus.Captured captured) {
        RrObservationSink target = sink.getIfAvailable();
        if (target == null) {
            return;
        }
        Row row = captured.row();
        for (RrExpectationEntity expectation : expectations.findByEnabledTrue()) {
            if (!captured.clusterId().equals(expectation.getClusterId())) {
                continue;
            }
            if (expectation.getRequestAddress().equals(row.address())) {
                target.accept(new Observation.RequestSeen(
                        captured.clusterId(),
                        row.nodeId(),
                        Instant.now(),
                        row.address(),
                        String.valueOf(row.messageId()),
                        correlationOf(row),
                        row.replyTo() == null ? null : CoreDestinationName.extract(row.replyTo()),
                        row.expiration(),
                        row.body(),
                        Map.of(),
                        row.timestamp() > 0 ? Instant.ofEpochMilli(row.timestamp()) : null));
            } else if (row.address() != null && replyAddresses.matches(expectation, row.address())) {
                target.accept(new Observation.ReplySeen(
                        captured.clusterId(),
                        row.nodeId(),
                        Instant.now(),
                        row.address(),
                        String.valueOf(row.messageId()),
                        correlationOf(row),
                        row.body(),
                        Map.of(),
                        row.timestamp() > 0 ? Instant.ofEpochMilli(row.timestamp()) : null));
            }
        }
    }

    /** JMSCorrelationID first, then Artemis' own property — the same order the sampler uses. */
    private static String correlationOf(Row row) {
        if (row.correlationId() != null) {
            return row.correlationId();
        }
        Object property = row.properties() == null ? null : row.properties().get("_AMQ_CORRELATION_ID");
        return property == null ? null : property.toString();
    }
}
