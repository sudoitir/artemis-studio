package io.github.sudoitir.artemisstudio.kernel.auditfeed;

import io.github.sudoitir.artemisstudio.kernel.audit.AuditDelivery;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditReceiver;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditRecord;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginHandle;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.descriptor.PluginDescriptor;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.ApplicationContext;

/** A plugin as the host would hand it to the audit bridge, without a jar. */
final class AuditReceiverFixtures {

    private AuditReceiverFixtures() {}

    /** A receiver whose behaviour a test sets, remembering every batch it was given. */
    static final class TestReceiver implements AuditReceiver {

        final List<AuditDelivery> batches = new CopyOnWriteArrayList<>();
        volatile Exception failure;
        volatile Runnable beforeAnswer = () -> {};

        @Override
        public void receive(AuditDelivery delivery) throws Exception {
            batches.add(delivery);
            beforeAnswer.run();
            if (failure != null) {
                throw failure;
            }
        }

        /** The ids of the records whose action starts with {@code prefix}, in the order they arrived. */
        List<Long> ids(String prefix) {
            return batches.stream()
                    .flatMap(b -> b.records().stream())
                    .filter(r -> r.action().startsWith(prefix))
                    .map(AuditRecord::id)
                    .toList();
        }

        List<AuditRecord> records(int batch, String prefix) {
            return batches.get(batch).records().stream()
                    .filter(r -> r.action().startsWith(prefix))
                    .toList();
        }
    }

    record FakeHandle(String id, boolean receive, boolean retentionHold, Map<Class<?>, Object> beans)
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
