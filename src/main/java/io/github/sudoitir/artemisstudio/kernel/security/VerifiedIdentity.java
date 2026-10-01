package io.github.sudoitir.artemisstudio.kernel.security;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import java.util.Set;

/**
 * What a {@link PluginCredentialProvider} says about a user whose credentials matched. Studio
 * rejects an answer with a blank subject or username, a field longer than 255 characters, or more
 * than 1,000 groups.
 *
 * @param subject the provider's stable identifier for the user; it keys the account, so it must not change
 * @param username the name to create the account with on first sign-in
 * @param email the user's email, or {@code null}
 * @param groups the groups the user belongs to, matched against the provider's group mappings
 */
@PluginApi
public record VerifiedIdentity(String subject, String username, String email, Set<String> groups) {

    public VerifiedIdentity {
        groups = groups == null ? Set.of() : Set.copyOf(groups);
    }
}
