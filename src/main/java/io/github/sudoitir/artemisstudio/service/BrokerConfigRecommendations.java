package io.github.sudoitir.artemisstudio.service;

import io.github.sudoitir.artemisstudio.broker.BrokerCapabilities;
import io.github.sudoitir.artemisstudio.broker.BrokerCapabilities.CapabilityAssessment;
import io.github.sudoitir.artemisstudio.broker.BrokerCapabilities.CapabilityStatus;
import io.github.sudoitir.artemisstudio.broker.BrokerXmlSnippets;
import io.github.sudoitir.artemisstudio.broker.CapabilityProbe;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.BrokerConfigDocument;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.BrokerConfigDocument.AddressSettingDecl;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.BrokerConfigDocument.SecuritySettingDecl;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.ObservedNodeConfig;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.PermissionType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * What the capability probe found, turned into configuration Studio can apply
 * (ADR-0068).
 *
 * <p>The probe already tells an operator the exact {@code broker.xml} that would
 * close each gap. Three of those fragments are address settings or security
 * settings, which the management API takes at runtime and {@code ADR-0065} keeps
 * across a restart — so for those, handing the operator a snippet to paste is
 * making them do by hand what the product can do for them. The rest
 * ({@code <broker-plugins>}, {@code <acceptors>}, the management security setting
 * that also needs {@code artemis-roles.properties}) will never be appliable, and
 * say so by name rather than by omission.
 *
 * <p>Two rules keep this honest:
 *
 * <ul>
 *   <li><b>Seeded from observed.</b> {@code addAddressSettings} replaces the whole
 *       entry rather than merging into it (notes §15 M2), so a recommendation that
 *       carried only its own keys would silently reset every other key on the
 *       match. Each recommendation starts from what the node currently resolves for
 *       that match and adds its own on top, so applying it changes exactly what it
 *       says it changes.
 *   <li><b>Nothing is applied here.</b> This class is a pure read. The revision it
 *       describes is saved, planned and applied through the ordinary path, with the
 *       ordinary canary, hazards and typed confirmation (ADR-0067 D8).
 * </ul>
 */
public final class BrokerConfigRecommendations {

    /** The roles used when a broker reports none to copy, and the operator has not chosen. */
    static final String FALLBACK_ROLE_NOTE =
            "No role currently holds consume on this address, so Studio has nothing to copy."
                    + " Name the role your management account holds before applying.";

    private BrokerConfigRecommendations() {}

    public enum Section {
        ADDRESS_SETTING,
        SECURITY_SETTING
    }

    /**
     * One thing the operator could have Studio write, or one thing they must write
     * themselves.
     *
     * @param capability the capability whose gap this closes, matching the ledger row
     * @param appliable whether Studio can write it over the management API
     * @param values for an address setting: the full entry that would be written,
     *     observed keys included (never a partial entry — see M2 above)
     * @param roles for a security setting: permission type to role names, prefilled
     *     from what the broker reports and editable before it is applied
     * @param manualSnippet the {@code broker.xml} that is still needed, or null
     */
    public record Recommendation(
            String capability,
            String title,
            String rationale,
            boolean appliable,
            Section section,
            String match,
            Map<String, Object> values,
            Map<PermissionType, Set<String>> roles,
            List<String> keys,
            String manualSnippet) {

        public Recommendation {
            values = values == null ? Map.of() : Map.copyOf(values);
            roles = roles == null ? Map.of() : Map.copyOf(roles);
            keys = keys == null ? List.of() : List.copyOf(keys);
        }
    }

    /**
     * @param seededFrom the node the observed values were read from, or null when no
     *     node could be read — then nothing is seeded and the caller must say so
     */
    public record Recommendations(String seededFrom, List<Recommendation> recommendations) {

        public boolean anyAppliable() {
            return recommendations.stream().anyMatch(Recommendation::appliable);
        }
    }

    /**
     * @param observed the node to seed from — the same node the capabilities were
     *     probed against. An unreadable or absent observation yields recommendations
     *     with no seed, which the caller reports rather than applying blind.
     */
    public static Recommendations from(BrokerCapabilities capabilities, ObservedNodeConfig observed) {
        boolean seeded = observed != null && observed.readable();
        Map<String, Object> catchAll = seeded ? observed.addressSettings().getOrDefault("#", Map.of()) : Map.of();
        List<Recommendation> out = new ArrayList<>();

        if (truncating(capabilities.messageIo())) {
            out.add(addressSetting(
                    "messageIo",
                    "Return whole message bodies to Studio",
                    "The broker truncates management-returned bodies and properties at"
                            + " management-message-attribute-size-limit (256 bytes by default) and appends"
                            + " ', + N more'. Setting it to -1 removes the cap so a browsed message shows its"
                            + " whole body.",
                    catchAll,
                    Map.of("managementMessageAttributeSizeLimit", -1L),
                    null));
        }

        if (gap(capabilities.slowConsumerDetection())) {
            out.add(addressSetting(
                    "slowConsumerDetection",
                    "Let the broker detect slow consumers",
                    "The broker sees each consumer's own delivery rate, which Studio cannot: its"
                            + " CONSUMER_SLOW notification names the consumer, where Studio's derived rule can"
                            + " only resolve to a queue on a node.",
                    catchAll,
                    new LinkedHashMap<>(Map.of(
                            "slowConsumerThreshold",
                            1L,
                            "slowConsumerThresholdMeasurementUnit",
                            "MESSAGES_PER_SECOND",
                            "slowConsumerCheckPeriod",
                            5L,
                            "slowConsumerPolicy",
                            "NOTIFY")),
                    BrokerXmlSnippets.NOTIFICATION_PLUGIN));
        }

        if (gap(capabilities.notifications())) {
            out.add(notificationsSecurity(observed, seeded));
        }

        // Always named, never applied: there is no management operation for any of
        // them, and a gap the operator cannot see is a gap they cannot close.
        out.add(new Recommendation(
                "notifications",
                "Emit connection, session, delivered and expired events",
                "NotificationActiveMQServerPlugin is a broker-plugin. No management operation"
                        + " installs one, so this stays a broker.xml edit and a restart. Without it those"
                        + " four notification classes are never emitted, whatever the permissions say.",
                false,
                null,
                null,
                Map.of(),
                Map.of(),
                List.of(),
                BrokerXmlSnippets.NOTIFICATION_PLUGIN));

        if (capabilities.managementWrite().status() == CapabilityStatus.UNAVAILABLE) {
            out.add(new Recommendation(
                    "managementWrite",
                    "Grant this account management-write access",
                    "Two gates refuse independently: the broker's security settings on"
                            + " activemq.management, and the console/Jolokia layer in front of them, which"
                            + " needs a role granted in artemis-roles.properties. A cluster that cannot write"
                            + " also cannot apply this for itself.",
                    false,
                    null,
                    null,
                    Map.of(),
                    Map.of(),
                    List.of(),
                    BrokerXmlSnippets.MANAGEMENT_SECURITY_SETTING));
        }

        return new Recommendations(seeded ? observed.nodeName() : null, List.copyOf(out));
    }

    /**
     * The declaration these recommendations describe, merged onto a starting
     * document. Address settings for the same match collapse into one entry, so two
     * recommendations on {@code #} cannot each replace the other's keys.
     */
    public static BrokerConfigDocument merge(BrokerConfigDocument base, List<Recommendation> selected) {
        Map<String, Map<String, Object>> settings = new LinkedHashMap<>();
        base.addressSettings().forEach(s -> settings.put(s.match(), new TreeMap<>(s.values())));
        Map<String, Map<PermissionType, Set<String>>> security = new LinkedHashMap<>();
        base.securitySettings().forEach(s -> security.put(s.match(), new LinkedHashMap<>(s.permissions())));

        for (Recommendation r : selected) {
            if (!r.appliable()) {
                continue;
            }
            if (r.section() == Section.ADDRESS_SETTING) {
                settings.computeIfAbsent(r.match(), k -> new TreeMap<>()).putAll(r.values());
            } else if (r.section() == Section.SECURITY_SETTING) {
                security.computeIfAbsent(r.match(), k -> new LinkedHashMap<>()).putAll(r.roles());
            }
        }

        return new BrokerConfigDocument(
                BrokerConfigDocument.CURRENT_VERSION,
                base.addresses(),
                settings.entrySet().stream()
                        .map(e -> new AddressSettingDecl(e.getKey(), e.getValue()))
                        .toList(),
                security.entrySet().stream()
                        .map(e -> new SecuritySettingDecl(e.getKey(), e.getValue()))
                        .toList(),
                base.diverts());
    }

    private static Recommendation addressSetting(
            String capability,
            String title,
            String rationale,
            Map<String, Object> seed,
            Map<String, Object> own,
            String manualSnippet) {
        Map<String, Object> values = new TreeMap<>(seed);
        values.putAll(own);
        return new Recommendation(
                capability,
                title,
                rationale,
                true,
                Section.ADDRESS_SETTING,
                "#",
                values,
                Map.of(),
                List.copyOf(own.keySet()),
                manualSnippet);
    }

    /**
     * Artemis matches the single most-specific security-setting (§15 M5), so the
     * {@code activemq.notifications} block must restate every permission a subscriber
     * needs — it inherits nothing from {@code #}. The roles are copied from whoever
     * currently holds {@code consume} on that address, falling back to {@code #},
     * because inventing a role name would produce a block that applies cleanly and
     * grants nobody anything.
     */
    private static Recommendation notificationsSecurity(ObservedNodeConfig observed, boolean seeded) {
        Set<String> roles = new LinkedHashSet<>();
        if (seeded) {
            roles.addAll(consumers(observed, "activemq.notifications"));
            if (roles.isEmpty()) {
                roles.addAll(consumers(observed, "#"));
            }
        }
        Map<PermissionType, Set<String>> permissions = new LinkedHashMap<>();
        for (PermissionType type : List.of(
                PermissionType.CONSUME,
                PermissionType.CREATE_NON_DURABLE_QUEUE,
                PermissionType.DELETE_NON_DURABLE_QUEUE)) {
            permissions.put(type, Set.copyOf(roles));
        }
        return new Recommendation(
                "notifications",
                "Let Studio subscribe to broker notifications",
                "A Core subscriber needs consume AND createNonDurableQueue AND"
                        + " deleteNonDurableQueue on activemq.notifications. Artemis matches the single"
                        + " most-specific security-setting, so this block restates all three rather than"
                        + " inheriting them."
                        + (roles.isEmpty() ? " " + FALLBACK_ROLE_NOTE : ""),
                true,
                Section.SECURITY_SETTING,
                "activemq.notifications",
                Map.of(),
                permissions,
                List.of("consume", "createNonDurableQueue", "deleteNonDurableQueue"),
                null);
    }

    private static Set<String> consumers(ObservedNodeConfig observed, String match) {
        Map<PermissionType, Set<String>> entry = observed.securitySettings().get(match);
        return entry == null ? Set.of() : entry.getOrDefault(PermissionType.CONSUME, Set.of());
    }

    /** A capability is a gap unless it is plainly available. UNKNOWN counts (ADR-0049 D5). */
    private static boolean gap(CapabilityAssessment assessment) {
        return assessment.status() != CapabilityStatus.AVAILABLE;
    }

    /**
     * Message I/O reports AVAILABLE while still truncating, so its status is not the
     * question — the cap is. The probe says so in the reason exactly while the cap is
     * in force, which is also what makes this recommendation disappear once it has
     * been applied rather than reappearing for ever.
     */
    private static boolean truncating(CapabilityAssessment assessment) {
        return assessment.reason() != null && assessment.reason().contains(CapabilityProbe.TRUNCATING);
    }
}
