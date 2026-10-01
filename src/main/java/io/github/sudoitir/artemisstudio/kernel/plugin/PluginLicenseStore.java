package io.github.sudoitir.artemisstudio.kernel.plugin;

import io.github.sudoitir.artemisstudio.kernel.plugin.internal.descriptor.PluginDescriptorException;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.descriptor.PluginDescriptorParser;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.host.PluginRefusedException;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence.PluginInstallEntity;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence.PluginInstallRepository;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence.PluginLicenseEntity;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence.PluginLicenseRepository;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.validation.Violation;
import io.github.sudoitir.artemisstudio.kernel.replica.BusResumed;
import io.github.sudoitir.artemisstudio.kernel.replica.ReplicaSignal;
import io.github.sudoitir.artemisstudio.kernel.replica.StudioBus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores plugins' license files and hands each plugin its own {@link PluginLicense} (ADR-0153).
 *
 * <p>Studio never reads a file: the bytes go in and out as they came, and what they mean is the
 * plugin's to decide. A write publishes a {@link ReplicaSignal} in its own transaction, so every
 * replica, this one included, tells its copy of the plugin with a {@link PluginLicenseChanged}
 * once the write has committed. A content of a file never appears in an exception message.
 */
@Component
@RequiredArgsConstructor
public class PluginLicenseStore implements PluginScopedBeans {

    static final String BEAN_NAME = "pluginLicense";
    static final String SIGNAL = "plugin-license";

    /** The largest license file Studio accepts. */
    public static final int MAX_BYTES = 64 * 1024;

    /** How long before its expiry a valid license is shown as expiring. */
    public static final Duration EXPIRY_WARNING = Duration.ofDays(30);

    /** How long a file may wait for its plugin's verdict before the health says so. */
    public static final Duration UNCHECKED_GRACE = Duration.ofMinutes(5);

    private static final int MAX_LICENSEE = 200;
    private static final int MAX_DETAIL = 500;

    /** What an administrator is shown, derived from the stored file and the plugin's verdict. */
    public enum State {
        MISSING,
        UNCHECKED,
        VALID,
        EXPIRING,
        EXPIRED,
        OVER_LIMIT,
        INVALID
    }

    /** The license of one plugin as administrators see it; the file itself is never part of it. */
    public record Summary(
            State state,
            Instant expiresAt,
            String licensee,
            String detail,
            Instant uploadedAt,
            String uploadedBy,
            Instant reportedAt) {}

    private static final Summary MISSING = new Summary(State.MISSING, null, null, null, null, null, null);

    private final PluginLicenseRepository licenses;
    private final PluginInstallRepository installs;
    private final PluginDescriptorParser parser;
    private final StudioBus bus;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Override
    public Map<String, Object> beansFor(String pluginId) {
        return Map.of(BEAN_NAME, new PluginLicense(this, pluginId));
    }

    // ---- what a plugin does --------------------------------------------------------------------

    Optional<PluginLicense.LicenseFile> file(String pluginId) {
        return licenses.findById(pluginId)
                .map(row -> new PluginLicense.LicenseFile(row.getContent(), row.getSha256(), row.getUploadedAt()));
    }

    void report(String pluginId, String sha256, PluginLicense.Verdict verdict) {
        Objects.requireNonNull(verdict, "verdict");
        if (sha256 == null) {
            return;
        }
        Instant expiresAt =
                verdict.expiresAt() == null ? null : verdict.expiresAt().truncatedTo(ChronoUnit.MICROS);
        String licensee = cut(verdict.licensee(), MAX_LICENSEE);
        String detail = cut(verdict.detail(), MAX_DETAIL);
        String status = verdict.status().name();
        licenses.findById(pluginId)
                .filter(row -> sha256.equals(row.getSha256()))
                .filter(row -> !(status.equals(row.getStatus())
                        && Objects.equals(expiresAt, row.getExpiresAt())
                        && Objects.equals(licensee, row.getLicensee())
                        && Objects.equals(detail, row.getDetail())))
                .ifPresent(
                        row -> licenses.report(pluginId, sha256, status, expiresAt, licensee, detail, clock.instant()));
    }

    // ---- what an administrator does ------------------------------------------------------------

    /**
     * Stores {@code content} as the plugin's license, replacing any file, and clears the verdict.
     *
     * @return the file's SHA-256
     * @throws PluginRefusedException when the plugin is not installed, does not declare that it needs
     *     a license, or the file is empty or over {@value #MAX_BYTES} bytes
     */
    @Transactional
    public String put(String pluginId, byte[] content, String uploadedBy) {
        if (content.length == 0) {
            throw refused("license-empty", "The license file is empty.");
        }
        if (content.length > MAX_BYTES) {
            throw refused("license-too-large", "A license file may be at most 64 KiB.");
        }
        PluginInstallEntity install = installs.findById(pluginId)
                .filter(i -> i.status() != PluginInstallStatus.UNINSTALLED)
                .orElseThrow(() -> refused("not-found", "No installed plugin '" + pluginId + "'."));
        if (!requiresLicense(install)) {
            throw refused(
                    "license-not-required", "Plugin '" + pluginId + "' does not declare that it needs a license.");
        }
        PluginLicenseEntity row = licenses.findById(pluginId).orElseGet(() -> new PluginLicenseEntity(pluginId));
        String sha256 = sha256(content);
        row.setContent(content.clone());
        row.setSize(content.length);
        row.setSha256(sha256);
        row.setUploadedAt(clock.instant().truncatedTo(ChronoUnit.MICROS));
        row.setUploadedBy(uploadedBy);
        row.setStatus(null);
        row.setExpiresAt(null);
        row.setLicensee(null);
        row.setDetail(null);
        row.setReportedAt(null);
        licenses.save(row);
        bus.publish(new ReplicaSignal(SIGNAL, pluginId));
        return sha256;
    }

    /** Removes the plugin's license file. Allowed for a plugin that has stopped declaring a need for one. */
    @Transactional
    public void remove(String pluginId) {
        if (!licenses.existsById(pluginId)) {
            throw refused("not-found", "Plugin '" + pluginId + "' has no license file.");
        }
        licenses.deleteByPluginId(pluginId);
        bus.publish(new ReplicaSignal(SIGNAL, pluginId));
    }

    /** The plugin's license as administrators see it, or a missing one. */
    @Transactional(readOnly = true)
    public Summary summary(String pluginId) {
        return licenses.findById(pluginId).map(this::summarise).orElse(MISSING);
    }

    /** The license of every plugin that has a file; a plugin that is not here has none. */
    @Transactional(readOnly = true)
    public Map<String, Summary> summaries() {
        return load();
    }

    private Map<String, Summary> load() {
        Map<String, Summary> all = new HashMap<>();
        for (PluginLicenseEntity row : licenses.findAll()) {
            all.put(row.getPluginId(), summarise(row));
        }
        return all;
    }

    /** The license of every running plugin that declares a need for one, by plugin id, a missing one included. */
    @Transactional(readOnly = true)
    public Map<String, Summary> activeDeclaring() {
        Map<String, Summary> stored = load();
        Map<String, Summary> declaring = new TreeMap<>();
        for (PluginInstallEntity install : installs.findAll()) {
            if (install.status() == PluginInstallStatus.ACTIVE && requiresLicense(install)) {
                declaring.put(install.getId(), stored.getOrDefault(install.getId(), MISSING));
            }
        }
        return declaring;
    }

    /** The summary of a plugin with no file. */
    public static Summary missing() {
        return MISSING;
    }

    private Summary summarise(PluginLicenseEntity row) {
        State state = state(row);
        return new Summary(
                state,
                row.getExpiresAt(),
                row.getLicensee(),
                row.getDetail(),
                row.getUploadedAt(),
                row.getUploadedBy(),
                row.getReportedAt());
    }

    private State state(PluginLicenseEntity row) {
        if (row.getStatus() == null) {
            return State.UNCHECKED;
        }
        PluginLicense.Status status = PluginLicense.Status.valueOf(row.getStatus());
        Instant now = clock.instant();
        return switch (status) {
            case EXPIRED -> State.EXPIRED;
            case OVER_LIMIT -> State.OVER_LIMIT;
            case INVALID -> State.INVALID;
            case VALID -> {
                Instant expiresAt = row.getExpiresAt();
                if (expiresAt == null) {
                    yield State.VALID;
                }
                if (!expiresAt.isAfter(now)) {
                    yield State.EXPIRED;
                }
                yield expiresAt.isBefore(now.plus(EXPIRY_WARNING)) ? State.EXPIRING : State.VALID;
            }
        };
    }

    // ---- replicas and purge --------------------------------------------------------------------

    /** Another replica, or this one, wrote a license: tell the plugin here. */
    @EventListener(condition = "#signal.kind() == 'plugin-license'")
    public void onSignal(ReplicaSignal signal) {
        events.publishEvent(new PluginLicenseChanged(signal.key()));
    }

    /** The bus was down: a license may have changed in the gap, so every plugin with a file looks again. */
    @EventListener
    public void onBusResumed(BusResumed resumed) {
        for (PluginLicenseEntity row : licenses.findAll()) {
            events.publishEvent(new PluginLicenseChanged(row.getPluginId()));
        }
    }

    /** Runs inside the purge's own transaction (see {@link PluginPurged}). */
    @EventListener
    public void onPurged(PluginPurged purged) {
        licenses.deleteByPluginId(purged.pluginId());
    }

    // ---- helpers -------------------------------------------------------------------------------

    private boolean requiresLicense(PluginInstallEntity install) {
        try {
            return parser.parse(install.getDescriptor().getBytes(StandardCharsets.UTF_8))
                    .isRequiresLicense();
        } catch (PluginDescriptorException _) {
            return false;
        }
    }

    private static PluginRefusedException refused(String code, String message) {
        return new PluginRefusedException(List.of(new Violation(code, message, "")));
    }

    private static String cut(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max);
    }

    /** The lowercase hex SHA-256 of a file, as {@link PluginLicense.LicenseFile#sha256()} states it. */
    public static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
