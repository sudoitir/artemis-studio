package com.acme.notes;

import io.github.sudoitir.artemisstudio.kernel.jobs.ScheduledJob;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
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
public class NotesConfiguration {

    /**
     * A background job, run by Studio's scheduler. Never {@code @Scheduled}: Studio stops a
     * plugin's jobs when it stops the plugin, which it cannot do for a {@code @Scheduled} method.
     * Pruning works on shared rows, so it is {@code INSTALLATION}: with several Studio instances on
     * one database it runs on one of them per tick. A job that works on this instance's own state
     * (a cache, a buffer) is {@code INSTANCE}.
     */
    @Bean
    ScheduledJob pruneOldNotes(NotesService notes) {
        return ScheduledJob.fixedDelay("acme-notes-prune", "acme-notes", ScheduledJob.Scope.INSTALLATION, () -> Duration.ofHours(1), notes::pruneOlderThanAYear);
    }
}
