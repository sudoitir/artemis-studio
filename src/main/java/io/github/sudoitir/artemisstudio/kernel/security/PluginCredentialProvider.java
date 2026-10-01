package io.github.sudoitir.artemisstudio.kernel.security;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import java.util.Optional;
import java.util.Set;

/**
 * A username-and-password sign-in a plugin offers (ADR-0153). The plugin declares it under
 * {@code identityProviders} in {@code plugin.json} and exposes one bean of this type per declared
 * id; it is offered only while the plugin runs and its signer is trusted.
 *
 * <p>A provider answers with who the credentials identify, never with a user or grants: Studio
 * keys the account by the declared provider id and {@link VerifiedIdentity#subject()}, applies the
 * provider's group mappings, and owns everything else on the sign-in path: throttling, account
 * lockout, second factors, the session and the audit trail. A wrong password is simply an empty
 * answer, and a throw or a wait of more than 5 seconds is treated as one.
 */
@PluginApi
public interface PluginCredentialProvider {

    /** The provider id declared in {@code plugin.json}: {@code <plugin id>:<name>}. */
    String id();

    /**
     * The identity these credentials belong to, or empty when they do not match.
     *
     * @param username what the user typed, or for a step-up the account's name at this provider
     */
    Optional<VerifiedIdentity> authenticate(String username, String password);

    /**
     * Which of {@code subjects} this provider no longer vouches for (a user removed or disabled at
     * the source). Studio asks about every enabled account of the provider about every five minutes,
     * in one call that may take up to 30 seconds, and ends the sessions, API tokens and trusted
     * devices of each subject returned; it ignores a subject it did not ask about. The account
     * stays enabled, so a user restored at the source signs in again. Nothing is revoked by default.
     */
    default Set<String> noLongerValid(Set<String> subjects) {
        return Set.of();
    }
}
