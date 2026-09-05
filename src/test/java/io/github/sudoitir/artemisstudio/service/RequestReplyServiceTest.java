package io.github.sudoitir.artemisstudio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudoitir.artemisstudio.persist.AuditEventEntity;
import io.github.sudoitir.artemisstudio.persist.AuditEventRepository;
import io.github.sudoitir.artemisstudio.persist.ClusterEntity;
import io.github.sudoitir.artemisstudio.persist.ClusterRepository;
import io.github.sudoitir.artemisstudio.support.AdminAuthenticationExtension;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import io.github.sudoitir.artemisstudio.web.dto.RrViews.CreateExpectationRequest;
import io.github.sudoitir.artemisstudio.web.dto.RrViews.ExpectationView;
import io.github.sudoitir.artemisstudio.web.dto.RrViews.UpdateExpectationRequest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Expectation CRUD and its audit trail (request-reply-tracing spec). */
@org.junit.jupiter.api.extension.ExtendWith(AdminAuthenticationExtension.class)
class RequestReplyServiceTest extends PostgresIntegrationTest {

    @Autowired
    RequestReplyService service;

    @Autowired
    ClusterRepository clusters;

    @Autowired
    AuditEventRepository audits;

    private UUID clusterId;

    @AfterEach
    void cleanUp() {
        if (clusterId != null) {
            clusters.deleteById(clusterId);
        }
    }

    private UUID cluster() {
        clusterId = clusters.save(new ClusterEntity("rr-svc-" + UUID.randomUUID(), null, null))
                .getId();
        return clusterId;
    }

    @Test
    void createIsAuditedAndEnabledByDefault() {
        UUID clusterId = cluster();
        ExpectationView created = service.create(
                clusterId, new CreateExpectationRequest("rr.request", "rr.reply", null, 30_000, 10, false));

        assertThat(created.enabled()).isTrue();
        assertThat(created.requestAddress()).isEqualTo("rr.request");
        assertThat(created.replyAddress()).isEqualTo("rr.reply");

        List<AuditEventEntity> events = audits.findByClusterIdOrderByTsDesc(clusterId);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().getAction()).isEqualTo("CREATE_RR_EXPECTATION");
        assertThat(events.getFirst().getOutcome()).isEqualTo("SUCCESS");
    }

    @Test
    void disablingRetainsConfiguration() {
        UUID clusterId = cluster();
        ExpectationView created =
                service.create(clusterId, new CreateExpectationRequest("rr.request", null, null, null, 5, true));

        ExpectationView disabled = service.update(
                clusterId,
                created.id(),
                new UpdateExpectationRequest(
                        created.replyAddress(),
                        created.correlationProperty(),
                        created.deadlineMs(),
                        created.samplePerMin(),
                        created.capturePayload(),
                        false));

        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.requestAddress()).isEqualTo("rr.request");
        assertThat(disabled.samplePerMin()).isEqualTo(5);
        assertThat(disabled.capturePayload()).isTrue();

        List<AuditEventEntity> events = audits.findByClusterIdOrderByTsDesc(clusterId);
        assertThat(events).hasSize(2);
        assertThat(events.getFirst().getAction()).isEqualTo("UPDATE_RR_EXPECTATION");
    }

    @Test
    void deleteRemovesTheExpectation() {
        UUID clusterId = cluster();
        ExpectationView created =
                service.create(clusterId, new CreateExpectationRequest("rr.request", null, null, null, 10, false));

        service.delete(clusterId, created.id());

        assertThat(service.list(clusterId)).isEmpty();
        assertThatThrownBy(() -> service.update(
                        clusterId, created.id(), new UpdateExpectationRequest(null, null, null, 10, false, true)))
                .isInstanceOf(NotFoundException.class);
    }
}
