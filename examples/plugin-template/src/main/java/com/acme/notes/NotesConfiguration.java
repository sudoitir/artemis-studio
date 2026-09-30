package com.acme.notes;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * The plugin's root configuration, named by plugin.json's {@code configuration}. Studio builds the
 * plugin its own Spring context from this class: a component scan of the plugin's own package,
 * with Studio's {@code @PluginApi} beans (audit, permissions, settings, the live stream, ...)
 * available to inject. Transactions, {@code @PreAuthorize} and JPA are already set up.
 */
@Configuration
@ComponentScan(basePackageClasses = NotesConfiguration.class)
public class NotesConfiguration {}
