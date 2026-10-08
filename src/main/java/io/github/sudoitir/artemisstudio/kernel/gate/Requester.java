package io.github.sudoitir.artemisstudio.kernel.gate;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import java.util.UUID;

/**
 * Who asked for an operation.
 *
 * @param tokenId the API token used, or {@code null} for a session
 */
@PluginApi
public record Requester(UUID userId, String username, AuthKind authKind, UUID tokenId) {}
