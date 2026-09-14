package io.github.sudoitir.artemisstudio.kernel.security;

import java.util.Set;

/**
 * Who an external identity provider says the caller is (ADR-0073). The provider establishes the
 * identity; {@link IdentityProvisioner} turns it into a Studio user and grants.
 *
 * @param providerId the provider's id, as listed at {@code /api/v1/auth/providers}
 * @param subject the provider's stable identifier for the user
 * @param username the name to create the user with on first sign-in
 * @param email the user's email, or {@code null}
 * @param groups the groups the provider reports, matched against its group mappings
 */
public record ExternalIdentity(String providerId, String subject, String username, String email, Set<String> groups) {}
