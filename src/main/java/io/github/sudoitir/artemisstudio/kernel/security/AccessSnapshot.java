package io.github.sudoitir.artemisstudio.kernel.security;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What one user may do, as of the moment it was loaded: their role grants, and the permissions their
 * team roles give them in each team they belong to, directly or through a directory group. Loaded by
 * {@link AccessLoader} and dropped whenever anything it was built from changes, so a decision never
 * rests on a sign-in's worth of old data.
 *
 * @param teamPermissions each team the user belongs to, with the union of the permissions of every team
 *     role they hold in it
 */
public record AccessSnapshot(Set<Grant> grants, Map<UUID, Set<String>> teamPermissions) {

    /** A user who may do nothing: unknown, or disabled. */
    public static final AccessSnapshot NONE = new AccessSnapshot(Set.of(), Map.of());

    public AccessSnapshot {
        grants = Set.copyOf(grants);
        teamPermissions = Map.copyOf(teamPermissions);
    }

    /** Whether the user's role in {@code teamId} gives them {@code permission}. */
    public boolean holdsInTeam(UUID teamId, String permission) {
        Set<String> held = teamPermissions.get(teamId);
        return held != null && Grant.covers(held, permission);
    }
}
