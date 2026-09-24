package io.github.sudoitir.artemisstudio.kernel.plugin.support;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginApi;

/**
 * A minimal {@link PluginApi} event, for tests that need to prove a plugin's own event listener
 * is (or is not) receiving republished core events — {@code CoreEventPluginBridge}'s Instant
 * update race regression test in particular. {@code filePath} tells the plugin's listener where
 * to record its receipt, since the listener runs inside the plugin's own classloader and cannot
 * share a Java reference back with the test.
 */
@PluginApi
public record RaceProbeEvent(String filePath, String text) {}
