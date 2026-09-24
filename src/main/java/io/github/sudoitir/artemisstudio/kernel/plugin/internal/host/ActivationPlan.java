package io.github.sudoitir.artemisstudio.kernel.plugin.internal.host;

import io.github.sudoitir.artemisstudio.kernel.plugin.internal.descriptor.PluginDescriptor;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.validation.ChangesetInfo;
import java.util.List;
import java.util.Map;

/**
 * design.md §5/§6/§8: what an activation would do, computed before anything is touched —
 * {@link io.github.sudoitir.artemisstudio.kernel.plugin.internal.host.PluginHost#plan(String)}
 * returns this for the review screen, and {@code activate} returns the same shape once it has
 * also refused what {@code plan} only reports (missing {@code requires}, the connection budget).
 *
 * @param fromVersion {@code null} for a fresh install
 * @param pendingChangesets every changeset the update would apply, in order, each already saying
 *     whether it carries a rollback
 * @param updateSql the SQL those changesets would run, without running it; empty when none are
 *     pending
 * @param rolesLosingPermission how many distinct roles hold each permission the update would
 *     remove — keyed by the permission action, only for {@code diff.permissionsRemoved()}
 * @param compatible whether the running Studio falls inside the plugin's declared
 *     {@code studio.since..until} (an unknown running version counts as compatible — design.md §3)
 * @param descriptor the jar's own descriptor — what the review screen describes
 * @param missingRequires every {@code requires} entry that is neither an enabled built-in feature
 *     nor an active plugin right now; non-empty refuses {@code activate} but not {@code plan}
 */
public record ActivationPlan(
        String pluginId,
        String fromVersion,
        String toVersion,
        ActivationClass activationClass,
        List<ChangesetInfo> pendingChangesets,
        String updateSql,
        ContributionDiff diff,
        Map<String, Integer> rolesLosingPermission,
        boolean compatible,
        List<String> missingRequires,
        PluginDescriptor descriptor,
        Restart restart) {

    /**
     * Whether confirming ends in a restart of Studio: {@code NONE}, {@code AUTOMATIC} (Studio
     * restarts itself once the new version is recorded, ADR-0104) or {@code MANUAL} (the operator
     * must restart it — nothing would start it again).
     */
    public enum Restart {
        NONE,
        AUTOMATIC,
        MANUAL
    }

    public ActivationPlan {
        pendingChangesets = List.copyOf(pendingChangesets);
        rolesLosingPermission = Map.copyOf(rolesLosingPermission);
        missingRequires = List.copyOf(missingRequires);
    }
}
