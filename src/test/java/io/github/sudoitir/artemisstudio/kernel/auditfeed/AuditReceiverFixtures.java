package io.github.sudoitir.artemisstudio.kernel.auditfeed;

import io.github.sudoitir.artemisstudio.kernel.audit.AuditDelivery;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditHold;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditReceiver;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditRecord;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditRetentionHold;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginHandle;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.descriptor.PluginDescriptor;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/** A plugin as the host would hand it to the audit bridge, without a jar. */
public final class AuditReceiverFixtures {

    private AuditReceiverFixtures() {}

    /** A receiver whose behaviour a test sets, remembering every batch it was given. */
    public static final class TestReceiver implements AuditReceiver {

        public final List<AuditDelivery> batches = new CopyOnWriteArrayList<>();
        public volatile Exception failure;
        public volatile Runnable beforeAnswer = () -> {};

        @Override
        public void receive(AuditDelivery delivery) throws Exception {
            batches.add(delivery);
            beforeAnswer.run();
            if (failure != null) {
                throw failure;
            }
        }

        /** The ids of the records whose action starts with {@code prefix}, in the order they arrived. */
        public List<Long> ids(String prefix) {
            return batches.stream()
                    .flatMap(b -> b.records().stream())
                    .filter(r -> r.action().startsWith(prefix))
                    .map(AuditRecord::id)
                    .toList();
        }

        public List<AuditRecord> records(int batch, String prefix) {
            return batches.get(batch).records().stream()
                    .filter(r -> r.action().startsWith(prefix))
                    .toList();
        }
    }

    /** A hold provider whose answer a test sets. */
    public static final class TestHold implements AuditRetentionHold {

        public volatile List<AuditHold> holds = List.of();
        public volatile Exception failure;
        public volatile Runnable beforeAnswer = () -> {};

        @Override
        public List<AuditHold> holds() throws Exception {
            beforeAnswer.run();
            if (failure != null) {
                throw failure;
            }
            return holds;
        }
    }

    /** An active install row declaring {@code audit.retentionHold}, which is what makes the purge ask the plugin. */
    public static void installHolder(JdbcTemplate jdbc, String id) {
        String sha = "audit-holder-" + id;
        jdbc.update(
                "INSERT INTO plugin_artifact (uploaded_at, size_bytes, sha256, content) VALUES (now(), 1, ?, ?)",
                sha,
                new byte[] {1});
        jdbc.update(
                "INSERT INTO plugin_install (installed_at, updated_at, id, version, vendor, sha256, status, installed_by,"
                        + " descriptor, schema_changed) VALUES (now(), now(), ?, '1.0.0', 'Acme', ?, 'active', 'test',"
                        + " '{\"audit\":{\"retentionHold\":true}}'::jsonb, false)",
                id,
                sha);
    }

    public static void removeHolder(JdbcTemplate jdbc, String id) {
        jdbc.update("DELETE FROM plugin_install WHERE id = ?", id);
        jdbc.update("DELETE FROM plugin_artifact WHERE sha256 = ?", "audit-holder-" + id);
    }

    public record FakeHandle(String id, boolean receive, boolean retentionHold, Map<Class<?>, Object> beans)
            implements PluginHandle {

        @Override
        public PluginDescriptor descriptor() {
            return new PluginDescriptor(
                    1,
                    id,
                    id,
                    "1.0.0",
                    new PluginDescriptor.Vendor("Acme", null, null),
                    "desc",
                    "Apache-2.0",
                    null,
                    "com.acme.audit",
                    "com.acme.Config",
                    12,
                    new PluginDescriptor.Studio("2026.01.0", null),
                    List.of(),
                    false,
                    false,
                    PluginDescriptor.Activation.AUTO,
                    null,
                    id,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    null,
                    new PluginDescriptor.AuditAccess(receive, retentionHold));
        }

        @Override
        public ApplicationContext applicationContext() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ClassLoader classLoader() {
            return getClass().getClassLoader();
        }

        @Override
        public boolean verified() {
            return true;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Map<String, T> beansOfType(Class<T> type) {
            Object bean = beans.get(type);
            return bean == null ? Map.of() : Map.of("bean", (T) bean);
        }

        @Override
        public <T> T runInPlugin(Callable<T> call) throws Exception {
            return call.call();
        }
    }
}
