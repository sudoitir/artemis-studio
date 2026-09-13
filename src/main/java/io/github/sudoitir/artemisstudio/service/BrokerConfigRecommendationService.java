package io.github.sudoitir.artemisstudio.service;

import io.github.sudoitir.artemisstudio.broker.BrokerCapabilities;
import io.github.sudoitir.artemisstudio.broker.brokerconfig.BrokerConfigOperations.ReadScope;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.BrokerConfigDocument;
import io.github.sudoitir.artemisstudio.domain.brokerconfig.ObservedNodeConfig;
import io.github.sudoitir.artemisstudio.persist.BrokerNodeEntity;
import io.github.sudoitir.artemisstudio.security.Permissions;
import io.github.sudoitir.artemisstudio.service.BrokerConfigRecommendations.Recommendation;
import io.github.sudoitir.artemisstudio.service.BrokerConfigRecommendations.Recommendations;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The capability probe's recommendations, and the one action that declares them
 * (ADR-0068).
 *
 * <p>Declaring is deliberately the whole of it. The recommendations become an
 * ordinary revision through {@link BrokerConfigService#save}, and applying that
 * revision is the ordinary apply with its ordinary gates — plan, hazards, canary,
 * typed confirmation. There is no shortcut that writes to a broker from here,
 * because a recommendation is a suggestion and ADR-0067 D8 only ever lets an
 * operator act.
 */
@Service
@RequiredArgsConstructor
public class BrokerConfigRecommendationService {

    private final ClusterService clusters;
    private final BrokerConfigReads reads;
    private final BrokerConfigService config;
    private final ClusterAccessGuard clusterAccess;

    /** What the probe suggests, seeded from the first readable live node. */
    @Transactional(readOnly = true)
    public Recommendations recommend(UUID clusterId) {
        clusterAccess.requireCluster(clusterId, Permissions.CLUSTER_READ);
        BrokerCapabilities capabilities = clusters.brokerCapabilities(clusterId);
        return BrokerConfigRecommendations.from(capabilities, seed(clusterId));
    }

    /**
     * Save the selected recommendations as a new revision, merged onto the current
     * declaration. Nothing is applied: the caller opens the plan next.
     *
     * @param capabilities which recommendations to take, by capability name; empty
     *     means every appliable one
     * @param roleOverrides the roles the operator chose for a security setting,
     *     keyed by match — the observed prefill is a starting point, not a decision
     *     Studio gets to make on its own (ADR-0068)
     */
    @Transactional
    public BrokerConfigService.Declaration declare(
            UUID clusterId, Set<String> capabilities, Map<String, Set<String>> roleOverrides) {
        clusterAccess.requireCluster(clusterId, Permissions.CONFIG_WRITE);
        Recommendations all = recommend(clusterId);
        List<Recommendation> selected = all.recommendations().stream()
                .filter(Recommendation::appliable)
                .filter(r -> capabilities == null || capabilities.isEmpty() || capabilities.contains(r.capability()))
                .map(r -> withRoles(r, roleOverrides))
                .toList();
        if (selected.isEmpty()) {
            throw new ConflictException(
                    "nothing-to-declare",
                    "None of this cluster's capability gaps can be closed from the declaration."
                            + " The remaining ones need a broker.xml edit and a restart.");
        }
        for (Recommendation r : selected) {
            if (r.section() == BrokerConfigRecommendations.Section.SECURITY_SETTING
                    && r.roles().values().stream().allMatch(Set::isEmpty)) {
                throw new ConflictException(
                        "roles-required",
                        "The security setting for " + r.match() + " would grant nobody anything."
                                + " Name at least one role before declaring it.");
            }
        }

        var current = config.current(clusterId);
        BrokerConfigDocument base =
                current.map(BrokerConfigService.CurrentRevision::document).orElseGet(BrokerConfigDocument::empty);
        return config.save(
                clusterId,
                BrokerConfigRecommendations.merge(base, selected),
                current.map(BrokerConfigService.CurrentRevision::revision).orElse(null),
                "Recommended configuration from the capability probe: "
                        + selected.stream()
                                .map(Recommendation::title)
                                .collect(java.util.stream.Collectors.joining("; ")),
                BrokerConfigService.Source.RECOMMENDED,
                null);
    }

    private static Recommendation withRoles(Recommendation r, Map<String, Set<String>> overrides) {
        Set<String> chosen = overrides == null ? null : overrides.get(r.match());
        if (chosen == null || r.section() != BrokerConfigRecommendations.Section.SECURITY_SETTING) {
            return r;
        }
        Map<io.github.sudoitir.artemisstudio.domain.brokerconfig.PermissionType, Set<String>> roles =
                r.roles().keySet().stream().collect(java.util.stream.Collectors.toMap(t -> t, t -> Set.copyOf(chosen)));
        return new Recommendation(
                r.capability(),
                r.title(),
                r.rationale(),
                r.appliable(),
                r.section(),
                r.match(),
                r.values(),
                roles,
                r.keys(),
                r.manualSnippet());
    }

    /**
     * The node the recommendations are seeded from: the same one the probe assessed,
     * read at the two matches a recommendation can touch. An unreadable node yields
     * an unseeded set, which the view reports rather than hiding.
     */
    private ObservedNodeConfig seed(UUID clusterId) {
        ReadScope scope =
                new ReadScope(Set.of("#"), Set.of("#", "activemq.notifications"), Set.of(), Map.of(), Set.of());
        for (BrokerNodeEntity node : reads.targets(clusterId)) {
            ObservedNodeConfig observed = reads.observe(clusterId, node, scope);
            if (observed.readable()) {
                return observed;
            }
        }
        return null;
    }
}
