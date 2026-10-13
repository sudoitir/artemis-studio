package io.github.sudoitir.artemisstudio.kernel.auditfeed;

import io.github.sudoitir.artemisstudio.kernel.audit.AuditReceiver;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditRetentionHold;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginBridge;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginHandle;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginInstallStatus;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginStatusChanged;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The audit receivers and retention holds attached on this replica (ADR-0190). A plugin whose
 * manifest declares {@code audit.receive} must provide exactly one {@link AuditReceiver}, and one
 * that declares {@code audit.retentionHold} exactly one {@link AuditRetentionHold}. A plugin that
 * provides either bean without declaring it is refused, so a plugin never sees or shapes the
 * trail unless that was acknowledged on install.
 *
 * <p>On attach a receiver gets its cursor at the start of the retained trail, so a new receiver is
 * given everything Studio still holds. The cursor is deleted when the plugin is uninstalled, not
 * when it is only disabled: a disabled receiver is expected back, and retention waits for it until
 * the longest it may.
 */
@Component
@RequiredArgsConstructor
public class AuditReceivers implements PluginBridge {

    /** A plugin bean attached on this replica, with the handle to call it through. */
    record Attached<T>(PluginHandle handle, T bean) {}

    private final JdbcTemplate jdbc;
    private final Map<String, Attached<AuditReceiver>> receivers = new ConcurrentHashMap<>();
    private final Map<String, Attached<AuditRetentionHold>> holds = new ConcurrentHashMap<>();

    @Override
    public void attach(PluginHandle handle) {
        AuditReceiver receiver = single(
                handle,
                AuditReceiver.class,
                "receive",
                handle.descriptor().audit().receive());
        AuditRetentionHold hold = single(
                handle,
                AuditRetentionHold.class,
                "retentionHold",
                handle.descriptor().audit().retentionHold());
        if (receiver == null) {
            jdbc.update("DELETE FROM audit_receiver WHERE plugin_id = ?", handle.id());
        } else {
            jdbc.update("INSERT INTO audit_receiver (plugin_id) VALUES (?) ON CONFLICT DO NOTHING", handle.id());
            receivers.put(handle.id(), new Attached<>(handle, receiver));
        }
        if (hold != null) {
            holds.put(handle.id(), new Attached<>(handle, hold));
        }
    }

    /** The one bean of {@code type} when the manifest declares {@code flag}, null when it does not; anything else refuses activation. */
    private static <T> T single(PluginHandle handle, Class<T> type, String flag, boolean declared) {
        var beans = handle.beansOfType(type);
        if (!declared) {
            if (!beans.isEmpty()) {
                throw new IllegalStateException(
                        "Plugin \"%s\" provides an %s but does not declare audit.%s in plugin.json"
                                .formatted(handle.id(), type.getSimpleName(), flag));
            }
            return null;
        }
        if (beans.size() != 1) {
            throw new IllegalStateException(
                    "Plugin \"%s\" declares audit.%s in plugin.json and must have exactly one %s bean, found %d"
                            .formatted(handle.id(), flag, type.getSimpleName(), beans.size()));
        }
        return beans.values().iterator().next();
    }

    @Override
    public void detach(PluginHandle handle) {
        receivers.computeIfPresent(handle.id(), (id, current) -> current.handle() == handle ? null : current);
        holds.computeIfPresent(handle.id(), (id, current) -> current.handle() == handle ? null : current);
    }

    /** The receivers attached on this replica. */
    List<Attached<AuditReceiver>> receivers() {
        return List.copyOf(receivers.values());
    }

    Attached<AuditReceiver> receiver(String pluginId) {
        return receivers.get(pluginId);
    }

    /** The retention hold of a plugin attached on this replica, null for any other. */
    Attached<AuditRetentionHold> hold(String pluginId) {
        return holds.get(pluginId);
    }

    /** Runs inside the transaction that uninstalls the plugin; its gaps go with the cursor. */
    @EventListener
    void onStatusChanged(PluginStatusChanged changed) {
        if (changed.to() == PluginInstallStatus.UNINSTALLED) {
            jdbc.update("DELETE FROM audit_receiver WHERE plugin_id = ?", changed.pluginId());
        }
    }
}
