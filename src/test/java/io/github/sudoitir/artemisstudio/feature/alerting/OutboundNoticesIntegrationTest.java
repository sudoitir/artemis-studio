package io.github.sudoitir.artemisstudio.feature.alerting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import io.github.sudoitir.artemisstudio.feature.alerting.NoticeMessage.Fact;
import io.github.sudoitir.artemisstudio.feature.alerting.NoticeMessage.Severity;
import io.github.sudoitir.artemisstudio.feature.alerting.internal.persistence.NotificationChannelEntity;
import io.github.sudoitir.artemisstudio.feature.alerting.internal.persistence.NotificationChannelRepository;
import io.github.sudoitir.artemisstudio.support.AdminAuthenticationExtension;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/** A plugin's notices on the delivery queue, against a real Postgres. */
@ExtendWith(AdminAuthenticationExtension.class)
class OutboundNoticesIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    NotificationChannelRepository channels;

    @Autowired
    NotificationChannels channelList;

    @Autowired
    OutboundNoticeService service;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    WebApplicationContext webContext;

    private final String source = "plugin-" + UUID.randomUUID();
    private UUID channelId;

    @BeforeEach
    void seed() {
        channelId = channels.save(new NotificationChannelEntity(
                        "notices-" + UUID.randomUUID(), "WEBHOOK", "{\"url\":\"https://x.example.com\"}", new byte[] {1
                        }))
                .getId();
    }

    @AfterEach
    void tearDown() {
        channels.deleteById(channelId); // cascades its deliveries
    }

    private NoticeMessage notice() {
        return new NoticeMessage(
                "Approval requested", "alice asks", Severity.WARNING, List.of(new Fact("By", "alice")), "/approvals/3");
    }

    private String key() {
        return UUID.randomUUID().toString();
    }

    private int rows() {
        return jdbc.queryForObject("SELECT count(*) FROM alert_delivery WHERE source = ?", Integer.class, source);
    }

    @Test
    void aNoticeIsQueuedForTheDispatcherAsAPendingRowWithItsSource() {
        service.enqueue(source, channelId, notice(), key());

        var row = jdbc.queryForMap(
                "SELECT kind, source, rule_id, state, payload::text AS payload FROM alert_delivery WHERE source = ?",
                source);
        assertThat(row).containsEntry("kind", "notice").containsEntry("state", "PENDING");
        assertThat(row.get("rule_id")).isNull();
        assertThat((String) row.get("payload")).contains("\"type\": \"notice\"", "\"title\": \"Approval requested\"");
        // The default test context has no public URL, so there is no link.
        assertThat((String) row.get("payload")).contains("\"url\": null");
    }

    @Test
    void aNoticeIsWrittenInTheCallersTransaction() {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                    service.enqueue(source, channelId, notice(), key());
                    throw new IllegalStateException("rolled back");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(rows()).isZero();
    }

    @Test
    void anUnknownChannelIsRefused() {
        assertThatThrownBy(() -> service.enqueue(source, UUID.randomUUID(), notice(), key()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown notification channel");
    }

    @Test
    void aNoticeOver8KbIsRefused() {
        List<Fact> facts = java.util.stream.IntStream.range(0, 10)
                .mapToObj(i -> new Fact("l" + i, "é".repeat(500)))
                .toList();
        NoticeMessage big = new NoticeMessage("t", "s".repeat(2000), Severity.INFO, facts, null);

        assertThatThrownBy(() -> service.enqueue(source, channelId, big, key()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("8192");
        assertThat(rows()).isZero();
    }

    @Test
    void aSourceIsLimitedTo600AnHourAndOtherSourcesAreNot() {
        jdbc.update(
                "INSERT INTO alert_delivery (kind, source, channel_id, payload)"
                        + " SELECT 'notice', ?, ?, '{}'::jsonb FROM generate_series(1, ?)",
                source,
                channelId,
                OutboundNotices.MAX_PER_HOUR - 1);

        service.enqueue(source, channelId, notice(), key());
        assertThatThrownBy(() -> service.enqueue(source, channelId, notice(), key()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("600");
        service.enqueue(source + "-other", channelId, notice(), key());
        assertThat(rows()).isEqualTo(OutboundNotices.MAX_PER_HOUR);
    }

    @Test
    void aRepeatedDedupeKeyIsANoOpPerSourceAndDoesNotCountTowardsTheCap() {
        jdbc.update(
                "INSERT INTO alert_delivery (kind, source, channel_id, payload)"
                        + " SELECT 'notice', ?, ?, '{}'::jsonb FROM generate_series(1, ?)",
                source,
                channelId,
                OutboundNotices.MAX_PER_HOUR - 1);

        service.enqueue(source, channelId, notice(), "event-7");
        // The source is now at the cap: a repeat is silent, while a new key is refused.
        service.enqueue(source, channelId, notice(), "event-7");
        assertThatThrownBy(() -> service.enqueue(source, channelId, notice(), "event-8"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("600");
        service.enqueue(source + "-other", channelId, notice(), "event-7");

        assertThat(rows()).isEqualTo(OutboundNotices.MAX_PER_HOUR);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM alert_delivery WHERE dedupe_key = 'event-7' AND source LIKE ?",
                        Integer.class,
                        source + "%"))
                .isEqualTo(2);
    }

    @Test
    void aDedupeKeyIsRequiredAndAtMost200Characters() {
        for (String bad : new String[] {null, "", "  ", "k".repeat(OutboundNotices.MAX_DEDUPE_KEY + 1)}) {
            assertThatThrownBy(() -> service.enqueue(source, channelId, notice(), bad))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("dedupe key");
        }
        service.enqueue(source, channelId, notice(), "k".repeat(OutboundNotices.MAX_DEDUPE_KEY));

        assertThat(rows()).isOne();
    }

    @Test
    void anOldNoticeDoesNotCountTowardsTheHour() {
        jdbc.update(
                "INSERT INTO alert_delivery (kind, source, channel_id, payload, created_at)"
                        + " SELECT 'notice', ?, ?, '{}'::jsonb, now() - interval '2 hours' FROM generate_series(1, ?)",
                source,
                channelId,
                OutboundNotices.MAX_PER_HOUR);

        service.enqueue(source, channelId, notice(), key());

        assertThat(rows()).isEqualTo(OutboundNotices.MAX_PER_HOUR + 1);
    }

    @Test
    void theChannelListHasNoConfigurationOrSecret() {
        ChannelSummary summary = channelList.list().stream()
                .filter(c -> c.id().equals(channelId))
                .findFirst()
                .orElseThrow();

        assertThat(summary.kind()).isEqualTo("WEBHOOK");
        assertThat(summary.enabled()).isTrue();
        assertThat(ChannelSummary.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("id", "name", "kind", "enabled");
        assertThat(summary.toString()).doesNotContain("x.example.com");
    }

    @Test
    void aNoticeShowsInTheDeliveryHistoryWithItsSource() throws Exception {
        service.enqueue(source, channelId, notice(), key());

        MockMvc mvc = webAppContextSetup(webContext).build();
        mvc.perform(get("/api/v1/channels/{id}/deliveries", channelId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].kind").value("notice"))
                .andExpect(jsonPath("$.data[0].source").value(source))
                .andExpect(jsonPath("$.data[0].ruleId").doesNotExist())
                .andExpect(jsonPath("$.data[0].summary").value("[WARNING] Approval requested"));
    }

    @Test
    void anAlertRowMustHaveARuleAndANoticeMustNot() {
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO alert_delivery (kind, channel_id, payload) VALUES ('alert', ?, '{}'::jsonb)",
                        channelId))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
