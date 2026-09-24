package io.github.sudoitir.artemisstudio.kernel.plugin.internal.host;

import java.util.Set;

/**
 * What an update would add or remove, compared to the currently active version (design.md §6's
 * "What changes" panel) — empty on every side for a fresh install, since there is nothing to
 * compare against.
 */
public record ContributionDiff(
        Set<String> permissionsAdded,
        Set<String> permissionsRemoved,
        Set<String> settingKeysAdded,
        Set<String> settingKeysRemoved,
        Set<String> streamTopicsAdded,
        Set<String> streamTopicsRemoved,
        Set<String> mcpToolsAdded,
        Set<String> mcpToolsRemoved) {}
