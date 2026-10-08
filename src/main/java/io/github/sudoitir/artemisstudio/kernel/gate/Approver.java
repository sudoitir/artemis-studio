package io.github.sudoitir.artemisstudio.kernel.gate;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import java.time.Instant;
import java.util.UUID;

/**
 * Who is voting, with the facts of their session so a policy can require a second factor.
 *
 * @param authenticatedAt when they last proved who they are
 * @param mfaVerifiedAt when they last passed a second factor, or {@code null}
 * @param mfaMethod the second factor used, such as {@code totp} or {@code webauthn}, or {@code null}
 */
@PluginApi
public record Approver(
        UUID userId, String username, Instant authenticatedAt, Instant mfaVerifiedAt, String mfaMethod) {}
