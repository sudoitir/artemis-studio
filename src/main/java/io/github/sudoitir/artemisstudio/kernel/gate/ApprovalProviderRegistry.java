package io.github.sudoitir.artemisstudio.kernel.gate;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginBridge;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginHandle;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginInstallStatus;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginStatusChanged;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Who the approval gate waits for (ADR-0179). {@link #armedProviderId()} is the arming test: one
 * indexed read of {@code plugin_install}, never cached, so it cannot be stale. It answers with the
 * plugin whose install declares {@code approvalProvider} and is meant to be running ({@link
 * PluginInstallStatus#desiredActive()}: starting, active, failed, needing a restart or
 * incompatible), so the gate stays armed while the provider is down and gated operations fail
 * closed. {@link #attached()} is the provider bean when its plugin context is attached on this
 * replica. As a {@link PluginBridge} it finds that bean when a plugin activates.
 *
 * <p>Arming and disarming are logged at INFO, and so is the state at boot.
 */
@Component
@Slf4j
public class ApprovalProviderRegistry implements PluginBridge {

    private static final String ARMED_SQL = "SELECT id FROM plugin_install WHERE approval_provider AND status IN ("
            + PluginInstallStatus.desiredActiveDbValues().stream()
                    .map(status -> "'" + status + "'")
                    .collect(Collectors.joining(", "))
            + ") ORDER BY id LIMIT 1";

    private record Attached(PluginHandle handle, ApprovalProvider provider) {}

    private final JdbcTemplate jdbc;
    private final Map<String, Attached> attached = new ConcurrentHashMap<>();
    private String lastArmed;

    public ApprovalProviderRegistry(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The plugin id of the approval provider that is meant to be running, whether or not it is. */
    public Optional<String> armedProviderId() {
        return jdbc.queryForList(ARMED_SQL, String.class).stream().findFirst();
    }

    /**
     * The provider of a plugin attached on this replica. Each call runs inside the plugin, with its
     * class loader and counted in flight so that an unload waits for it; a checked failure becomes an
     * {@link ApprovalUnavailableException}.
     */
    public Optional<ApprovalProvider> attached() {
        return attached.values().stream().map(Attached::provider).findFirst();
    }

    /**
     * The provider of plugin {@code pluginId} when it is attached on this replica, with the permission its
     * approvers need; empty for any other plugin, so a provider that is not the armed one is never asked.
     */
    public Optional<AttachedProvider> attached(String pluginId) {
        Attached found = pluginId == null ? null : attached.get(pluginId);
        return found == null
                ? Optional.empty()
                : Optional.of(new AttachedProvider(
                        pluginId,
                        found.handle().descriptor().approvalProvider().approverPermission(),
                        found.provider()));
    }

    /**
     * An attached provider.
     *
     * @param approverPermission the permission its plugin declares that approvers must hold
     */
    public record AttachedProvider(String pluginId, String approverPermission, ApprovalProvider provider) {}

    @Override
    public void attach(PluginHandle handle) {
        if (handle.descriptor().approvalProvider() == null) {
            return;
        }
        var beans = handle.beansOfType(ApprovalProvider.class);
        if (beans.size() != 1) {
            throw new IllegalStateException(
                    "Plugin \"%s\" declares approvalProvider in plugin.json and must have exactly one ApprovalProvider bean, found %d"
                            .formatted(handle.id(), beans.size()));
        }
        attached.put(
                handle.id(),
                new Attached(
                        handle,
                        new PluginBound(handle, beans.values().iterator().next())));
        observe(Optional.of(handle.id()), "attached on this replica");
    }

    @Override
    public void detach(PluginHandle handle) {
        Attached current = attached.get(handle.id());
        if (current != null && current.handle() == handle) {
            attached.remove(handle.id(), current);
        }
    }

    @EventListener
    void onReady(ApplicationReadyEvent ready) {
        Optional<String> armed = armedProviderId();
        synchronized (this) {
            lastArmed = armed.orElse(null);
        }
        log.info("approval-gate state={} provider={}", armed.isPresent() ? "armed" : "not-armed", armed.orElse("none"));
    }

    /** Runs inside the transaction that changed the row, which the arming read would not yet see. */
    @EventListener
    void onStatusChanged(PluginStatusChanged changed) {
        boolean wasProvider;
        synchronized (this) {
            wasProvider = changed.pluginId().equals(lastArmed);
        }
        if (wasProvider) {
            observe(Optional.empty(), "plugin %s by %s".formatted(changed.to().dbValue(), changed.actor()));
        }
    }

    private synchronized void observe(Optional<String> armed, String why) {
        String now = armed.orElse(null);
        if (java.util.Objects.equals(now, lastArmed)) {
            return;
        }
        lastArmed = now;
        log.info("approval-gate state={} provider={} reason=\"{}\"", now == null ? "disarmed" : "armed", now, why);
    }

    /** Runs {@code call} inside the plugin; a checked failure means the plugin could not answer. */
    static <T> T inPlugin(PluginHandle handle, Callable<T> call) {
        try {
            return handle.runInPlugin(call);
        } catch (RuntimeException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApprovalUnavailableException(
                    "The call into plugin \"%s\" was interrupted".formatted(handle.id()), e);
        } catch (Exception e) {
            throw new ApprovalUnavailableException("The call into plugin \"%s\" failed".formatted(handle.id()), e);
        }
    }

    private record PluginBound(PluginHandle handle, ApprovalProvider bean) implements ApprovalProvider {

        @Override
        public GateDecision decide(GateRequest request) {
            return inPlugin(handle, () -> bean.decide(request));
        }

        @Override
        public VoteCheck checkVote(HeldOperationView held, Approver approver, Vote vote) {
            return inPlugin(handle, () -> bean.checkVote(held, approver, vote));
        }

        @Override
        public RunCheck checkRun(HeldOperationView held, Effect now) {
            return inPlugin(handle, () -> bean.checkRun(held, now));
        }
    }
}
