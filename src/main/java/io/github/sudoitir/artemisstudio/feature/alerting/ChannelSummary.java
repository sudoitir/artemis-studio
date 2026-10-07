package io.github.sudoitir.artemisstudio.feature.alerting;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import java.util.UUID;

/**
 * A notification channel as a plugin sees it: enough to pick one, never its configuration or secret.
 *
 * @param kind {@code SLACK}, {@code TEAMS}, {@code WEBHOOK}, {@code EMAIL} or {@code PAGERDUTY}
 */
@PluginApi
public record ChannelSummary(UUID id, String name, String kind, boolean enabled) {}
