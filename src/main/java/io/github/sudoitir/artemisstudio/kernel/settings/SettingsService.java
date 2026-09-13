package io.github.sudoitir.artemisstudio.kernel.settings;

import io.github.sudoitir.artemisstudio.kernel.audit.AuditEventEntity;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditService;
import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureDescriptor;
import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureDisabledException;
import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureRegistry;
import io.github.sudoitir.artemisstudio.kernel.security.ActorResolver;
import io.github.sudoitir.artemisstudio.kernel.settings.internal.StudioSettingEntity;
import io.github.sudoitir.artemisstudio.kernel.settings.internal.StudioSettingRepository;
import io.github.sudoitir.artemisstudio.kernel.settings.web.SettingsViews.SettingValue;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The runtime configuration layer: a stored {@code studio_setting} row wins over the
 * packaged default, and deleting the row restores it. Nothing is ever seeded — an
 * absent row <em>means</em> "use the packaged default", which is what lets an upgrade
 * ship a new default instead of being pinned by a value written at first start.
 *
 * <p>The registry is assembled from the enabled modules' {@link SettingsContribution}s
 * (ADR-0047, ADR-0070). A setting of a disabled module is neither listed nor writable,
 * and its stored value is kept for when the module is enabled again.
 *
 * <p><b>Every key here applies without a restart.</b> There are two ways that happens,
 * and {@link SettingDef#apply()} is which one:
 *
 * <ul>
 *   <li><b>Pulled</b> ({@code apply == null}) — the consumer reads the value here each
 *       time it needs it, so the next read is already the new one.
 *   <li><b>Pushed</b> ({@code apply != null}) — the consumer holds the value in a
 *       {@code volatile} field because it is read too often to look up, so the
 *       registry pushes it there on boot and after every change.
 * </ul>
 *
 * <p>Cadences are neither: they are job triggers that re-read this service when
 * computing each next run (ADR-0025, ADR-0048), so a changed interval is honoured on
 * the following fire.
 *
 * <p>To add a setting: one {@link SettingDef} in the owning module's contribution and
 * its key in that module's descriptor. The API, the validation, the reset affordance,
 * the audit row and the Settings screen all follow from it.
 */
@Service
@Slf4j
public class SettingsService {

    private final StudioSettingRepository repo;
    private final AuditService audit;
    private final ActorResolver actorResolver;
    private final FeatureRegistry features;

    /** Insertion-ordered: this is also the order the settings screen renders. */
    private final Map<String, SettingDef> registry = new LinkedHashMap<>();

    /**
     * Every stored override, refreshed on boot and after each write. Reads are on the
     * scheduling hot path — a trigger asks for its interval on every fire — and writes
     * are the only thing that can invalidate this, all through {@link #applyRuntime()}.
     */
    private volatile Map<String, String> overrides = Map.of();

    public SettingsService(
            StudioSettingRepository repo,
            AuditService audit,
            ActorResolver actorResolver,
            FeatureRegistry features,
            List<SettingsContribution> contributions) {
        this.repo = repo;
        this.audit = audit;
        this.actorResolver = actorResolver;
        this.features = features;
        for (FeatureDescriptor module : features.enabled()) {
            for (SettingsContribution contribution : contributions) {
                if (!contribution.featureId().equals(module.id())) {
                    continue;
                }
                for (SettingDef def : contribution.settings()) {
                    if (!module.settingKeys().contains(def.key())) {
                        throw new IllegalStateException("Setting '" + def.key() + "' is contributed by '" + module.id()
                                + "' but not declared in its descriptor");
                    }
                    if (registry.putIfAbsent(def.key(), def) != null) {
                        throw new IllegalStateException("Setting '" + def.key() + "' is contributed twice");
                    }
                }
            }
            for (String key : module.settingKeys()) {
                if (!registry.containsKey(key)) {
                    throw new IllegalStateException("Module '" + module.id() + "' declares setting '" + key
                            + "' but contributes no definition");
                }
            }
        }
    }

    // ---- typed reads ------------------------------------------------------

    public Duration duration(String key) {
        return Duration.parse(toIso(value(key)));
    }

    public int intValue(String key) {
        return Integer.parseInt(value(key).trim());
    }

    /** The effective raw value: the stored override, else the packaged default. */
    public String value(String key) {
        String override = overrides.get(key);
        return override != null ? override : requireKnown(key).defaultValue().get();
    }

    // ---- read / write -----------------------------------------------------

    /** Every operator-tunable key of the enabled modules: its effective value, its default, and how to render it. */
    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.kernel.settings.SettingsPermissions).SETTINGS_READ)")
    public Map<String, SettingValue> effective() {
        Map<String, String> stored = overrides;
        Map<String, SettingValue> out = new LinkedHashMap<>();
        registry.forEach((key, spec) -> {
            String defaultValue = spec.defaultValue().get();
            String override = stored.get(key);
            out.put(
                    key,
                    new SettingValue(
                            override != null ? override : defaultValue,
                            override != null,
                            defaultValue,
                            spec.group(),
                            spec.label(),
                            spec.hint(),
                            spec.kind().name()));
        });
        return out;
    }

    /**
     * Audited in the same transaction as the write (non-negotiable #3), so a change
     * and the record of it commit or roll back together.
     *
     * <p>Validation happens <em>before</em> the audit row rather than after: a rejected
     * value never becomes a transaction, so a {@code begin}/{@code fail} pair around it
     * would roll back with everything else and record nothing.
     */
    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.kernel.settings.SettingsPermissions).SETTINGS_WRITE)")
    @Transactional
    public void put(String key, String rawValue) {
        SettingDef spec = requireKnown(key);
        String value = unquote(rawValue);
        validate(spec, value);

        AuditEventEntity event = audit.begin(
                actorResolver.resolve(),
                "UPDATE_SETTING",
                "SETTING",
                key,
                null,
                null,
                Map.of("from", overrides.getOrDefault(key, spec.defaultValue().get()), "to", value),
                false);

        String json = asJsonScalar(value);
        repo.findById(key).ifPresentOrElse(e -> e.setValue(json), () -> repo.save(new StudioSettingEntity(key, json)));
        repo.flush();
        applyRuntime();
        audit.succeed(event, 1);
    }

    /** Clears the override so the packaged default takes over again. Audited like {@link #put}. */
    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.kernel.settings.SettingsPermissions).SETTINGS_WRITE)")
    @Transactional
    public void reset(String key) {
        SettingDef spec = requireKnown(key);

        AuditEventEntity event = audit.begin(
                actorResolver.resolve(),
                "RESET_SETTING",
                "SETTING",
                key,
                null,
                null,
                Map.of(
                        "from", overrides.getOrDefault(key, spec.defaultValue().get()),
                        "to", spec.defaultValue().get()),
                false);

        repo.deleteById(key);
        repo.flush();
        applyRuntime();
        audit.succeed(event, 1);
    }

    /**
     * Re-read the stored overrides and push the cached ones to their holders. Runs on
     * boot and after every change. A stored value for a key no enabled module owns is
     * left in the table and ignored.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional(readOnly = true)
    public void applyRuntime() {
        Map<String, String> fresh = new LinkedHashMap<>();
        for (StudioSettingEntity row : repo.findAll()) {
            if (registry.containsKey(row.getKey())) {
                fresh.put(row.getKey(), unquote(row.getValue()));
            }
        }
        overrides = Map.copyOf(fresh);
        for (SettingDef spec : registry.values()) {
            if (spec.apply() != null) {
                spec.apply().accept(this);
            }
        }
    }

    // ---- helpers --------------------------------------------------------

    private SettingDef requireKnown(String key) {
        SettingDef spec = registry.get(key);
        if (spec != null) {
            return spec;
        }
        features.ownerOfSetting(key)
                .filter(owner -> !features.isEnabled(owner.id()))
                .ifPresent(owner -> {
                    throw new FeatureDisabledException(owner);
                });
        throw new IllegalArgumentException("Unknown setting key: " + key);
    }

    private static void validate(SettingDef spec, String value) {
        switch (spec.kind()) {
            case DURATION -> {
                Duration d = Duration.parse(toIso(value));
                if (d.isZero() || d.isNegative()) {
                    throw new IllegalArgumentException(spec.key() + " must be a positive duration");
                }
            }
            case INT -> {
                int n = Integer.parseInt(value.trim());
                if (n < 1) {
                    throw new IllegalArgumentException(spec.key() + " must be at least 1");
                }
            }
            case CRON -> validateCron(spec.key(), value.trim());
        }
    }

    /**
     * A cron that fires more often than once a minute is rejected rather than clamped.
     * These schedules drive bulk {@code DELETE}s and DDL; a typo in the seconds field
     * would otherwise turn a nightly trim into a permanent one.
     */
    private static void validateCron(String key, String value) {
        if (!CronExpression.isValidExpression(value)) {
            throw new IllegalArgumentException(key + " must be a six-field cron expression");
        }
        CronExpression cron = CronExpression.parse(value);
        LocalDateTime from = LocalDateTime.of(2000, 1, 1, 0, 0);
        LocalDateTime first = cron.next(from);
        LocalDateTime second = first == null ? null : cron.next(first);
        if (first != null && second != null && Duration.between(first, second).toSeconds() < 60) {
            throw new IllegalArgumentException(key + " must not fire more than once a minute");
        }
    }

    /** Accept both {@code "5s"} (Spring style) and {@code "PT5S"} (ISO-8601) duration strings. */
    private static String toIso(String value) {
        String v = value.trim();
        if (v.startsWith("P") || v.startsWith("p")) {
            return v.toUpperCase();
        }
        if (v.endsWith("ms")) {
            return "PT" + (Long.parseLong(v.substring(0, v.length() - 2).trim()) / 1000.0) + "S";
        }
        if (v.endsWith("s")) {
            return "PT" + v.substring(0, v.length() - 1).trim() + "S";
        }
        if (v.endsWith("m")) {
            return "PT" + v.substring(0, v.length() - 1).trim() + "M";
        }
        if (v.endsWith("h")) {
            return "PT" + v.substring(0, v.length() - 1).trim() + "H";
        }
        if (v.endsWith("d")) {
            return "P" + v.substring(0, v.length() - 1).trim() + "D";
        }
        return "PT" + v + "S";
    }

    /** Encode a bare value as a JSON scalar for the {@code jsonb} column: a number stays bare, anything else is quoted. */
    private static String asJsonScalar(String value) {
        String v = value.trim();
        if (v.matches("-?\\d+(\\.\\d+)?")) {
            return v;
        }
        return "\"" + v.replace("\"", "\\\"") + "\"";
    }

    /** Stored values are JSON scalars; strip the quotes from a JSON string. */
    private static String unquote(String jsonScalar) {
        String v = jsonScalar.trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            return v.substring(1, v.length() - 1).replace("\\\"", "\"");
        }
        return v;
    }
}
