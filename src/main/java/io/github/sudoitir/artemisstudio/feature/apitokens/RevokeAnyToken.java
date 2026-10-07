package io.github.sudoitir.artemisstudio.feature.apitokens;

import java.util.UUID;

/** The parameters of the gated {@code token.revoke-any} operation: an administrator revoking another user's token. */
public record RevokeAnyToken(UUID tokenId) {}
