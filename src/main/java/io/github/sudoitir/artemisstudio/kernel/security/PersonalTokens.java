package io.github.sudoitir.artemisstudio.kernel.security;

import java.util.UUID;

/**
 * Personal API tokens as the kernel needs to see them, implemented by the module that holds them
 * (ADR-0039). The kernel neither mints nor authenticates through it; it asks whether a token still
 * counts, so an event stream opened with one can end when the token stops being accepted, and revokes
 * every token of a user whose second factors an administrator reset.
 */
public interface PersonalTokens {

    /**
     * Whether the token would still authenticate its owner: not revoked, not expired, its owner enabled,
     * and not one minted without a second factor by an owner who now requires one.
     */
    boolean isLive(UUID tokenId);

    /** Revoke every token of the user that is not already revoked; the number revoked. */
    int revokeAllOf(UUID userId);
}
