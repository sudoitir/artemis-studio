package io.github.sudoitir.artemisstudio.kernel.plugin;

/**
 * The one audit seam for a plugin's lifecycle (design.md §7, task 6.8): {@code PluginHost} calls
 * every bean of this type after each activation step, alongside its own structured stdout log
 * line — the same "log every step" requirement the design gives two independent channels, because
 * plugins share the database role and could rewrite {@code audit_event} (design.md's "Risks"). A
 * future task group implements this against {@code AuditService} in a module that may depend on
 * {@code kernel.security}; {@code kernel.plugin} itself never does, so the contract lives here and
 * the implementation is injected the same way a {@link PluginBridge} is.
 *
 * <p>A listener that throws is logged and does not interrupt the activation it was reporting on.
 */
public interface PluginLifecycleListener {

    /**
     * One step of one activation. {@code fromVersion} is {@code null} for a fresh install;
     * {@code outcome} is a short word ({@code in-progress}, {@code succeeded}, {@code failed},
     * {@code needs-restart}), not a sentence — the message belongs in the log line, not here.
     */
    void onStep(
            String pluginId,
            String fromVersion,
            String toVersion,
            String sha256,
            String actor,
            String step,
            String outcome);
}
