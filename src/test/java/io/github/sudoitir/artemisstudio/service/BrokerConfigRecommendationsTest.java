package io.github.sudoitir.artemisstudio.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.broker.BrokerCapabilities;
import io.github.sudoitir.artemisstudio.broker.BrokerCapabilities.CapabilityAssessment;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.BrokerConfigDocument;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.BrokerConfigDocument.AddressSettingDecl;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.ObservedNodeConfig;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.PermissionType;
import io.github.sudoitir.artemisstudio.service.BrokerConfigRecommendations.Recommendation;
import io.github.sudoitir.artemisstudio.service.BrokerConfigRecommendations.Recommendations;
import io.github.sudoitir.artemisstudio.service.BrokerConfigRecommendations.Section;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The capability probe's hints, turned into configuration Studio can apply
 * (ADR-0068). The two things that must hold: a recommendation carries the whole
 * entry it would write, and a gap Studio cannot close is named rather than
 * omitted.
 */
class BrokerConfigRecommendationsTest {

    private static final UUID NODE = UUID.randomUUID();

    private static BrokerCapabilities allGaps() {
        CapabilityAssessment gap = CapabilityAssessment.unavailable("not configured");
        return new BrokerCapabilities(
                CapabilityAssessment.available("read ok"),
                CapabilityAssessment.available("write ok"),
                gap,
                CapabilityAssessment.available("Available. The broker "
                        + io.github.sudoitir.artemisstudio.broker.CapabilityProbe.TRUNCATING + "."),
                gap);
    }

    private static ObservedNodeConfig observed(
            Map<String, Object> catchAllSettings, Map<String, Map<PermissionType, Set<String>>> security) {
        return new ObservedNodeConfig(
                NODE,
                "primary",
                true,
                Map.of(),
                Map.of(),
                Map.of("#", catchAllSettings),
                security,
                Map.of(),
                Map.of(),
                null);
    }

    @Test
    void anAddressSettingRecommendationCarriesTheKeysTheNodeAlreadyHas() {
        // addAddressSettings replaces the entry rather than merging into it (§15 M2),
        // so a recommendation that carried only its own key would reset every other
        // key on '#' the moment it was applied.
        Recommendations r = BrokerConfigRecommendations.from(
                allGaps(), observed(Map.of("maxDeliveryAttempts", 7, "deadLetterAddress", "DLQ"), Map.of()));

        Recommendation slow = only(r, "slowConsumerDetection");
        assertThat(slow.appliable()).isTrue();
        assertThat(slow.match()).isEqualTo("#");
        assertThat(slow.values())
                .containsEntry("maxDeliveryAttempts", 7)
                .containsEntry("deadLetterAddress", "DLQ")
                .containsEntry("slowConsumerThreshold", 1L)
                .containsEntry("slowConsumerPolicy", "NOTIFY");
        // keys names only what this recommendation itself decided.
        assertThat(slow.keys())
                .containsExactlyInAnyOrder(
                        "slowConsumerThreshold",
                        "slowConsumerThresholdMeasurementUnit",
                        "slowConsumerCheckPeriod",
                        "slowConsumerPolicy");
    }

    @Test
    void notificationRolesArePrefilledFromWhoeverCanConsumeThere() {
        Recommendations r = BrokerConfigRecommendations.from(
                allGaps(),
                observed(
                        Map.of(),
                        Map.of(
                                "activemq.notifications",
                                Map.of(PermissionType.CONSUME, Set.of("amq")),
                                "#",
                                Map.of(PermissionType.CONSUME, Set.of("everyone")))));

        Recommendation notify = appliable(r, "notifications");
        assertThat(notify.section()).isEqualTo(Section.SECURITY_SETTING);
        // The address's own roles win over the catch-all's, and all three permissions
        // are restated because Artemis matches the single most-specific block (§15 M5).
        assertThat(notify.roles())
                .containsEntry(PermissionType.CONSUME, Set.of("amq"))
                .containsEntry(PermissionType.CREATE_NON_DURABLE_QUEUE, Set.of("amq"))
                .containsEntry(PermissionType.DELETE_NON_DURABLE_QUEUE, Set.of("amq"));
    }

    @Test
    void aBrokerThatNamesNoConsumerGivesEmptyRolesAndSaysSo() {
        Recommendations r = BrokerConfigRecommendations.from(allGaps(), observed(Map.of(), Map.of()));

        Recommendation notify = appliable(r, "notifications");
        assertThat(notify.roles().values())
                .allSatisfy(roles -> assertThat(roles).isEmpty());
        assertThat(notify.rationale()).contains(BrokerConfigRecommendations.FALLBACK_ROLE_NOTE);
    }

    @Test
    void theGapsStudioCannotCloseAreNamedRatherThanOmitted() {
        BrokerCapabilities refusedWrite = new BrokerCapabilities(
                CapabilityAssessment.available("read ok"),
                CapabilityAssessment.unavailable("refused"),
                CapabilityAssessment.unavailable("no"),
                CapabilityAssessment.available("ok"),
                CapabilityAssessment.available("ok"));

        List<Recommendation> manual =
                BrokerConfigRecommendations.from(refusedWrite, observed(Map.of(), Map.of())).recommendations().stream()
                        .filter(x -> !x.appliable())
                        .toList();

        assertThat(manual).extracting(Recommendation::capability).contains("notifications", "managementWrite");
        assertThat(manual).allSatisfy(m -> assertThat(m.manualSnippet()).isNotBlank());
    }

    @Test
    void twoRecommendationsOnTheSameMatchCollapseIntoOneEntry() {
        Recommendations r =
                BrokerConfigRecommendations.from(allGaps(), observed(Map.of("maxSizeBytes", 100L), Map.of()));

        BrokerConfigDocument merged =
                BrokerConfigRecommendations.merge(BrokerConfigDocument.empty(), r.recommendations());

        List<AddressSettingDecl> catchAll = merged.addressSettings().stream()
                .filter(s -> s.match().equals("#"))
                .toList();
        assertThat(catchAll).hasSize(1);
        assertThat(catchAll.getFirst().values())
                .containsEntry("maxSizeBytes", 100L)
                .containsEntry("managementMessageAttributeSizeLimit", -1L)
                .containsEntry("slowConsumerThreshold", 1L);
    }

    @Test
    void anUnreadableNodeSeedsNothingAndSaysWhich() {
        Recommendations r =
                BrokerConfigRecommendations.from(allGaps(), ObservedNodeConfig.unreachable(NODE, "primary", "down"));

        assertThat(r.seededFrom()).isNull();
        assertThat(r.anyAppliable()).isTrue();
        // Without a seed the entry holds only its own keys; the view reports the
        // missing seed so an operator is not shown a replace they cannot check.
        assertThat(only(r, "messageIo").values()).containsOnlyKeys("managementMessageAttributeSizeLimit");
    }

    @Test
    void anAvailableCapabilityIsNotRecommended() {
        BrokerCapabilities fine = new BrokerCapabilities(
                CapabilityAssessment.available("read ok"),
                CapabilityAssessment.available("write ok"),
                CapabilityAssessment.available("subscribed"),
                CapabilityAssessment.available("whole bodies"),
                CapabilityAssessment.available("threshold 1"));

        List<Recommendation> appliable =
                BrokerConfigRecommendations.from(fine, observed(Map.of(), Map.of())).recommendations().stream()
                        .filter(Recommendation::appliable)
                        .toList();

        assertThat(appliable).isEmpty();
    }

    private static Recommendation only(Recommendations r, String capability) {
        return r.recommendations().stream()
                .filter(x -> x.capability().equals(capability))
                .findFirst()
                .orElseThrow();
    }

    private static Recommendation appliable(Recommendations r, String capability) {
        return r.recommendations().stream()
                .filter(x -> x.capability().equals(capability) && x.appliable())
                .findFirst()
                .orElseThrow();
    }
}
