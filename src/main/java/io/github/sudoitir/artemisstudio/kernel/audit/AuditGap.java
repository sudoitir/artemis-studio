package io.github.sudoitir.artemisstudio.kernel.audit;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;
import java.time.Instant;

/**
 * Records a receiver will never get because retention removed them before it acknowledged them:
 * how many, and the time of the oldest and newest of them.
 */
@PluginApi
public record AuditGap(long count, Instant from, Instant to) {}
