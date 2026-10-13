package io.github.sudoitir.artemisstudio.kernel.auditfeed;

import io.github.sudoitir.artemisstudio.kernel.audit.AuditReceiver;
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
 * The audit receivers attached on this replica (ADR-0190). A plugin whose manifest declares {@code
 * audit.receive} must provide exactly one {@link AuditReceiver}, and one that provides the bean
 * without declaring it is refused, so a plugin never sees the trail unless it was acknowledged on
 * install. On attach the receiver gets its cursor at the start of the retained trail, so a new
 * receiver is given everything Studio still holds. The cursor is deleted when the plugin is
 * uninstalled, not when it is only disabled: a disabled receiver is expected back, and retention
 * waits for it until the longest it may.
 */
@Component
@RequiredArgsConstructor
public class AuditReceivers implements PluginBridge {

    /** A receiver attached on this replica. */
    record Attached(PluginHandle handle, AuditReceiver receiver) {}

    private final JdbcTemplate jdbc;
    private final Map<String, Attached> attached = new ConcurrentHashMap<>();

    @Override
    public void attach(PluginHandle handle) {
        var beans = handle.beansOfType(AuditReceiver.class);
        if (!handle.descriptor().audit().receive()) {
            if (!beans.isEmpty()) {
                throw new IllegalStateException(
                        "Plugin \"%s\" provides an AuditReceiver but does not declare audit.receive in plugin.json"
                                .formatted(handle.id()));
            }
            jdbc.update("DELETE FROM audit_receiver WHERE plugin_id = ?", handle.id());
            return;
        }
        if (beans.size() != 1) {
            throw new IllegalStateException(
                    "Plugin \"%s\" declares audit.receive in plugin.json and must have exactly one AuditReceiver bean, found %d"
                            .formatted(handle.id(), beans.size()));
        }
        jdbc.update("INSERT INTO audit_receiver (plugin_id) VALUES (?) ON CONFLICT DO NOTHING", handle.id());
        attached.put(handle.id(), new Attached(handle, beans.values().iterator().next()));
    }

    @Override
    public void detach(PluginHandle handle) {
        Attached current = attached.get(handle.id());
        if (current != null && current.handle() == handle) {
            attached.remove(handle.id(), current);
        }
    }

    /** The receivers attached on this replica. */
    List<Attached> attached() {
        return List.copyOf(attached.values());
    }

    Attached attached(String pluginId) {
        return attached.get(pluginId);
    }

    /** Runs inside the transaction that uninstalls the plugin; its gaps go with the cursor. */
    @EventListener
    void onStatusChanged(PluginStatusChanged changed) {
        if (changed.to() == PluginInstallStatus.UNINSTALLED) {
            jdbc.update("DELETE FROM audit_receiver WHERE plugin_id = ?", changed.pluginId());
        }
    }
}
