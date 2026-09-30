package io.github.sudoitir.artemisstudio.kernel.lifecycle;

import io.github.sudoitir.artemisstudio.kernel.settings.SettingDef;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsService;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/** What the Data page reads and writes (ADR-0132). Policy writes go through the settings store, so they are audited. */
@Service
@RequiredArgsConstructor
public class LifecycleService {

    /**
     * One store with its policy, usage and last purge.
     *
     * @param usage {@code null} when reading it failed; {@code usageError} then says why
     * @param lastPurge {@code null} before the first purge
     */
    public record StoreState(
            RegisteredStore store,
            String retention,
            int quota,
            int quotaWarnPercent,
            StoreUsage usage,
            String usageError,
            PurgeStatus.Last lastPurge) {

        /** Usage as a share of the quota, in percent; {@code null} without a quota or usage. */
        public Integer quotaUsedPercent() {
            if (quota <= 0 || usage == null) {
                return null;
            }
            long used = store.def().quotaUnit() == StoreDef.QuotaUnit.BYTES
                    ? usage.bytes() / (1024 * 1024)
                    : usage.rows() / 1000;
            return (int) Math.min(Integer.MAX_VALUE, used * 100 / quota);
        }

        public boolean overWarning() {
            Integer used = quotaUsedPercent();
            return used != null && used >= quotaWarnPercent;
        }
    }

    private final LifecycleRegistry registry;
    private final SettingsService settings;
    private final PurgeStatus status;
    private final Clock clock;

    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.kernel.lifecycle.DataPermissions).DATA_READ)")
    public List<StoreState> stores() {
        return states();
    }

    /** Every store's state, for callers already inside the system (alert evaluation), without a permission check. */
    public List<StoreState> states() {
        Map<String, PurgeStatus.Last> last = status.all();
        return registry.all().stream()
                .map(store -> {
                    StoreUsage usage = null;
                    String error = null;
                    try {
                        usage = store.call(ManagedStore::usage);
                    } catch (RuntimeException e) {
                        error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    }
                    return new StoreState(
                            store,
                            registry.retentionValue(store.id()),
                            registry.quota(store.id()),
                            registry.quotaWarnPercent(store.id()),
                            usage,
                            error,
                            last.get(store.id()));
                })
                .toList();
    }

    /** What a purge with {@code retention} would remove now. Validated like a write; deletes nothing. */
    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.kernel.lifecycle.DataPermissions).DATA_READ)")
    public PurgeEstimate preview(String storeId, String retention) {
        RegisteredStore store = registry.require(storeId);
        settings.check(LifecycleSettings.key(storeId, LifecycleSettings.RETENTION), retention);
        if (SettingDef.FOREVER.equalsIgnoreCase(retention.trim())) {
            return new PurgeEstimate(0, 0);
        }
        Duration keep = Duration.parse(SettingsService.toIso(retention));
        return store.call(s -> s.preview(clock.instant().minus(keep)));
    }

    /**
     * Sets a store's policy. Every value is checked before any is written, so a rejected policy
     * changes nothing; each written value is its own {@code UPDATE_SETTING} audit event, guarded by
     * {@code data:write}.
     */
    public void update(String storeId, String retention, int quota, int quotaWarnPercent) {
        registry.require(storeId);
        Map<String, String> values = Map.of(
                LifecycleSettings.key(storeId, LifecycleSettings.RETENTION), retention.trim(),
                LifecycleSettings.key(storeId, LifecycleSettings.QUOTA), Integer.toString(quota),
                LifecycleSettings.key(storeId, LifecycleSettings.QUOTA_WARN_PERCENT),
                        Integer.toString(quotaWarnPercent));
        values.forEach(settings::check);
        values.forEach((key, value) -> {
            if (!settings.value(key).equals(value)) {
                settings.put(key, value);
            }
        });
    }
}
