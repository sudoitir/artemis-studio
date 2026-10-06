package io.github.sudoitir.artemisstudio.kernel.security;

import java.util.UUID;

/**
 * An environment or cluster is going away, and the grants scoped to it are being dropped
 * ({@link ScopedGrants#revoke}). Published synchronously inside the deleting transaction, so what a
 * listener holds that is scoped to it, such as an API token's grants, is dropped with it or not at all.
 */
public record ScopeGrantsRevoked(String scopeType, UUID scopeId) {}
