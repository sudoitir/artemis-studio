package io.github.sudoitir.artemisstudio.kernel.security;

import java.util.UUID;

/**
 * A request was refused for lack of a permission, by {@link ClusterAccessGuard}. Published synchronously, in
 * the refused request, so the audit trail can record who was refused what (audit-log spec).
 *
 * @param clusterId the cluster the request was about
 * @param permission what the caller lacked
 * @param resource the queue or address it was about, or {@code null} for the cluster as a whole; for a
 *     pattern, a resource named by the pattern
 * @param hidden whether the caller was told it does not exist, rather than that they may not do this to it
 */
public record AccessRefused(UUID clusterId, String permission, ResourceRef resource, boolean hidden) {}
