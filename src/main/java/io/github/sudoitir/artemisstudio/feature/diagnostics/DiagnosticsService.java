package io.github.sudoitir.artemisstudio.feature.diagnostics;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditEvent;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditService;
import io.github.sudoitir.artemisstudio.kernel.core.NotFoundException;
import io.github.sudoitir.artemisstudio.kernel.core.SecretRedactor;
import io.github.sudoitir.artemisstudio.kernel.plugin.Contract;
import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureRegistry;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.host.PluginHost;
import io.github.sudoitir.artemisstudio.kernel.security.Actor;
import io.github.sudoitir.artemisstudio.kernel.security.ActorResolver;
import io.github.sudoitir.artemisstudio.kernel.settings.SecretRotationService;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsService;
import io.github.sudoitir.artemisstudio.platform.clusters.ClusterService;
import java.lang.management.ManagementFactory;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.management.ThreadDumpEndpoint;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.registry.HealthContributorRegistry;
import org.springframework.boot.info.BuildProperties;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Builds the support bundle and the bug-report summary in-process (diagnostics spec, ADR-0146). A bundle is a
 * snapshot: {@link #prepare} gathers and redacts every section once, and {@link #take} hands back exactly those
 * sections, so what is downloaded is what was previewed. No section reads a message.
 */
@Service
public class DiagnosticsService {

    static final Duration SNAPSHOT_TTL = Duration.ofMinutes(10);
    private static final int MAX_SNAPSHOTS = 32;
    private static final Pattern MASKS = Pattern.compile(Pattern.quote(SecretRedactor.MASK));

    private final ObjectProvider<BuildProperties> build;
    private final SettingsService settings;
    private final SecretRotationService secrets;
    private final ClusterService clusters;
    private final FeatureRegistry features;
    private final PluginHost plugins;
    private final HealthContributorRegistry health;
    private final DataSource dataSource;
    private final ActorResolver actors;
    private final AuditService audit;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Cache<UUID, Snapshot> snapshots;

    public DiagnosticsService(
            ObjectProvider<BuildProperties> build,
            SettingsService settings,
            SecretRotationService secrets,
            ClusterService clusters,
            FeatureRegistry features,
            PluginHost plugins,
            HealthContributorRegistry health,
            DataSource dataSource,
            ActorResolver actors,
            AuditService audit,
            ObjectMapper mapper,
            Clock clock) {
        this.build = build;
        this.settings = settings;
        this.secrets = secrets;
        this.clusters = clusters;
        this.features = features;
        this.plugins = plugins;
        this.health = health;
        this.dataSource = dataSource;
        this.actors = actors;
        this.audit = audit;
        this.mapper = mapper;
        this.clock = clock;
        this.snapshots = Caffeine.newBuilder()
                .maximumSize(MAX_SNAPSHOTS)
                .expireAfterWrite(SNAPSHOT_TTL)
                .build();
    }

    /** One part of a bundle: its text is final and redacted, {@code redactions} counts the masks in it. */
    public record Section(String key, String title, String fileName, String content, int redactions) {}

    /** A prepared bundle, usable only by {@code owner} until {@code expiresAt}. */
    public record Snapshot(UUID id, String owner, Instant createdAt, Instant expiresAt, List<Section> sections) {}

    /** What a bug report needs to know about this installation. Any signed-in user may read it. */
    public record Summary(String studioVersion, int contractVersion, String java, String os, String database) {}

    public Summary summary() {
        Runtime.Version java = Runtime.version();
        return new Summary(
                studioVersion(),
                Contract.VERSION,
                "%s (%s)".formatted(java, System.getProperty("java.vendor")),
                "%s %s (%s)"
                        .formatted(
                                System.getProperty("os.name"),
                                System.getProperty("os.version"),
                                System.getProperty("os.arch")),
                SecretRedactor.redact(database()));
    }

    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.feature.diagnostics.DiagnosticsPermissions).BUNDLE)")
    public Snapshot prepare() {
        Instant now = clock.instant();
        List<Section> sections = List.of(
                section("about", "About", "about.json", () -> json(about(now))),
                section("settings", "Settings", "settings.json", () -> json(settingsSection())),
                section("health", "Health", "health.json", () -> json(healthSection())),
                section("plugins", "Features and plugins", "plugins.json", () -> json(pluginsSection())),
                section("threads", "Thread dump", "threads.txt", () -> new ThreadDumpEndpoint().textThreadDump()),
                section("logs", "Logs", "logs.txt", DiagnosticsService::logs));
        Snapshot snapshot =
                new Snapshot(UUID.randomUUID(), actors.resolve().username(), now, now.plus(SNAPSHOT_TTL), sections);
        snapshots.put(snapshot.id(), snapshot);
        return snapshot;
    }

    /**
     * The kept sections of a snapshot this user prepared, audited as the bundle's creation. An unknown, expired or
     * foreign snapshot is not found, so its id reveals nothing.
     */
    @PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.feature.diagnostics.DiagnosticsPermissions).BUNDLE)")
    public Snapshot take(UUID id, List<String> keep) {
        Actor actor = actors.resolve();
        Snapshot snapshot = snapshots.getIfPresent(id);
        if (snapshot == null || !snapshot.owner().equals(actor.username())) {
            throw new NotFoundException("Support bundle", id);
        }
        Set<String> wanted = new LinkedHashSet<>(keep);
        if (wanted.isEmpty()) {
            throw new IllegalArgumentException("Keep at least one section.");
        }
        List<Section> kept = snapshot.sections().stream()
                .filter(s -> wanted.contains(s.key()))
                .toList();
        if (kept.size() != wanted.size()) {
            throw new IllegalArgumentException("Unknown section in " + wanted + ".");
        }
        List<String> keys = kept.stream().map(Section::key).toList();
        AuditEvent event = audit.begin(
                actor,
                "CREATE_DIAGNOSTICS_BUNDLE",
                "DIAGNOSTICS_BUNDLE",
                id.toString(),
                null,
                null,
                Map.of("sections", keys),
                false);
        audit.succeed(event, keys.size());
        return new Snapshot(snapshot.id(), snapshot.owner(), snapshot.createdAt(), snapshot.expiresAt(), kept);
    }

    /** Redacts the section's final text, so a value that slipped past its own masking is still caught. */
    private static Section section(String key, String title, String fileName, Supplier<String> content) {
        String text;
        try {
            text = SecretRedactor.redact(content.get());
        } catch (RuntimeException e) {
            text = SecretRedactor.redact("This section could not be collected: " + e);
        }
        return new Section(
                key, title, fileName, text, (int) MASKS.matcher(text).results().count());
    }

    private String json(Object value) {
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
    }

    private Map<String, Object> about(Instant now) {
        Runtime runtime = Runtime.getRuntime();
        var jvm = ManagementFactory.getRuntimeMXBean();
        Map<String, Object> about = new LinkedHashMap<>();
        about.put("generatedAt", now);
        about.put("studio", studioVersion());
        about.put(
                "builtAt",
                build.stream()
                        .map(BuildProperties::getTime)
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElse(null));
        about.put("pluginContract", Contract.VERSION);
        about.put(
                "java",
                Map.of(
                        "version", Runtime.version().toString(),
                        "vendor", System.getProperty("java.vendor"),
                        "vm", jvm.getVmName()));
        about.put(
                "os",
                Map.of(
                        "name", System.getProperty("os.name"),
                        "version", System.getProperty("os.version"),
                        "arch", System.getProperty("os.arch")));
        about.put("processors", runtime.availableProcessors());
        about.put(
                "memory",
                Map.of(
                        "maxBytes", runtime.maxMemory(),
                        "totalBytes", runtime.totalMemory(),
                        "freeBytes", runtime.freeMemory()));
        about.put("startedAt", Instant.ofEpochMilli(jvm.getStartTime()));
        about.put("uptime", Duration.ofMillis(jvm.getUptime()).toString());
        about.put("database", database());
        about.put(
                "clusters",
                clusters.list().stream()
                        .map(c -> Map.of("name", c.name(), "health", c.health(), "nodes", c.nodeCount()))
                        .toList());
        return about;
    }

    private Map<String, Object> settingsSection() {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> values = new LinkedHashMap<>();
        settings.effective()
                .forEach((key, v) ->
                        values.put(key, Map.of("value", String.valueOf(v.value()), "overridden", v.overridden())));
        out.put("settings", values);
        var status = secrets.status();
        out.put(
                "secretKeys",
                Map.of(
                        "provider", String.valueOf(status.provider()),
                        "currentVersion", status.currentVersion(),
                        "availableVersions", status.availableVersions(),
                        "missingVersions", status.missingVersions(),
                        "secretsByVersion", status.countsByVersion()));
        return out;
    }

    /** Every indicator with its details, which the web endpoint shows only to an authorised caller. */
    private Map<String, Object> healthSection() {
        Map<String, Object> out = new LinkedHashMap<>();
        health.stream().forEach(entry -> {
            if (entry.contributor() instanceof HealthIndicator indicator) {
                try {
                    var h = indicator.health(true);
                    out.put(entry.name(), Map.of("status", h.getStatus().getCode(), "details", h.getDetails()));
                } catch (RuntimeException e) {
                    out.put(entry.name(), Map.of("status", "ERROR", "error", e.toString()));
                }
            }
        });
        return out;
    }

    private Map<String, Object> pluginsSection() {
        List<Map<String, Object>> builtIn = features.all().stream()
                .map(d -> Map.<String, Object>of(
                        "id", d.id(), "kind", d.kind().name(), "enabled", features.isEnabled(d.id())))
                .toList();
        List<Map<String, Object>> installed = new ArrayList<>();
        for (var p : plugins.list()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", p.id());
            row.put("version", p.version());
            row.put("vendor", p.vendor());
            row.put("status", p.status().name());
            row.put("verified", p.verified());
            row.put("signer", p.signerFingerprint());
            row.put("failure", p.failure());
            row.put("installedAt", p.installedAt());
            row.put("activatedAt", p.activatedAt());
            installed.add(row);
        }
        return Map.of("safeMode", plugins.safeMode(), "builtIn", builtIn, "plugins", installed);
    }

    private static String logs() {
        List<String> lines = LogRingBuffer.attached().lines();
        return "# The last %d log lines since Studio started (at most %d). Earlier lines are in the container log.%n"
                        .formatted(lines.size(), LogRingBuffer.CAPACITY)
                + String.join("", lines);
    }

    private String studioVersion() {
        return build.stream().map(BuildProperties::getVersion).findFirst().orElse("unknown");
    }

    private String database() {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData meta = connection.getMetaData();
            return meta.getDatabaseProductName() + " " + meta.getDatabaseProductVersion();
        } catch (SQLException e) {
            return "unknown";
        }
    }
}
