package io.github.sudoitir.artemisstudio.kernel.plugin;

/**
 * Published by {@code PluginHost#purge} inside the same transaction that drops the plugin's
 * schema and deletes its {@code role_permission} and {@code studio_setting} rows (design.md §4,
 * task 6.8). {@code kernel.plugin} may not depend on {@code feature.apitokens}, so this is the SPI
 * a listener there uses to delete that plugin's {@code api_token_grant} rows (action prefix
 * {@code <pluginId>:}) without a cross-module table write: a plain {@code @EventListener} runs
 * synchronously, on the same thread and inside the same transaction, before {@code purge} commits.
 */
public record PluginPurged(String pluginId) {}
