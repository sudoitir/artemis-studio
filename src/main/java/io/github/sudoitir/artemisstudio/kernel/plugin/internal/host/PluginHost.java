package io.github.sudoitir.artemisstudio.kernel.plugin.internal.host;

import io.github.sudoitir.artemisstudio.kernel.core.ShutdownPhases;
import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureRegistry;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginInstallStatus;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginLifecycleListener;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginProperties;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginPurged;
import io.github.sudoitir.artemisstudio.kernel.plugin.SemVer;
import io.github.sudoitir.artemisstudio.kernel.plugin.StudioVersion;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.descriptor.PluginDescriptor;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.descriptor.PluginDescriptorException;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.descriptor.PluginDescriptorParser;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence.PluginInstallEntity;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.persistence.PluginInstallRepository;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.runtime.PluginMigrations;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.runtime.PluginMigrations.PluginMigrationException;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.runtime.PluginRuntime;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.runtime.PluginRuntimeFactory;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.runtime.PluginRuntimeRegistry;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.runtime.PluginRuntimeRegistry.Active;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.store.PluginStore;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.validation.ChangesetInfo;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.validation.PluginValidator;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.validation.ValidationReport;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.validation.Violation;
import jakarta.servlet.ServletContext;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one place that decides and runs a plugin activation (design.md §2/§5/§6, task 6.8, this
 * session's slice — {@link #list()}, {@link #status(String)}, {@link #plan(String)} and
 * {@link #activate(String, String)} only; the {@code SmartLifecycle} boot/shutdown/crash-loop
 * half of task 6.8 is a later slice).
 *
 * <p>{@link #plan(String)} is a pure read: it materializes the artifact, re-validates it, and
 * reports what an activation would do without touching anything. {@link #activate(String, String)}
 * repeats that same computation, additionally refuses what {@code plan} only reports (a required
 * plugin/feature not active, the connection budget), persists the {@code activating} transition
 * synchronously so the caller's next poll already sees it, and then runs the activation itself on
 * its own virtual thread — {@link PluginRuntimeFactory#activate} already does the actual context
 * build, migration and bridge attach/detach; this class only sequences those calls per
 * {@link ActivationClass} and decides what "the old version resumes" means on failure.
 */
@Component
@Slf4j
public class PluginHost implements SmartLifecycle {

    /** design.md §2: "the previous 3 boots within 15 minutes have no stopped_at" trips safe mode. */
    private static final int CRASH_LOOP_BOOT_COUNT = 3;

    private static final Duration CRASH_LOOP_WINDOW = Duration.ofMinutes(15);

    private static final String SYSTEM_ACTOR = "system";

    private final PluginStore store;
    private final PluginValidator validator;
    private final PluginMigrations migrations;
    private final PluginRuntimeFactory runtimeFactory;
    private final PluginRuntimeRegistry registry;
    private final FeatureRegistry featureRegistry;
    private final PluginInstallRepository installs;
    private final PluginDescriptorParser descriptorParser;
    private final StudioVersion studioVersion;
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final ObjectProvider<PluginLifecycleListener> listeners;
    private final ServletContext servletContext;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;
    private final PluginProperties properties;
    private final StudioRestart restart;

    /**
     * One lifecycle operation at a time, Studio-wide (design.md §7: "at most one activation in
     * flight"): activate, enable, rollback, disable, uninstall, purge and the boot sequence all hold
     * it. A {@link Semaphore}, not a lock, because an activation acquires it on the request thread
     * and releases it on its own virtual thread once it finishes.
     */
    private final Semaphore busy = new Semaphore(1);

    private volatile boolean running;
    private volatile Long currentBootId;
    private volatile boolean safeMode;
    private volatile String safeModeReason;

    public PluginHost(
            PluginStore store,
            PluginValidator validator,
            PluginMigrations migrations,
            PluginRuntimeFactory runtimeFactory,
            PluginRuntimeRegistry registry,
            FeatureRegistry featureRegistry,
            PluginInstallRepository installs,
            PluginDescriptorParser descriptorParser,
            StudioVersion studioVersion,
            DataSource dataSource,
            JdbcTemplate jdbc,
            JsonMapper json,
            ObjectProvider<PluginLifecycleListener> listeners,
            ServletContext servletContext,
            ApplicationEventPublisher events,
            PlatformTransactionManager transactionManager,
            PluginProperties properties,
            StudioRestart restart) {
        this.store = store;
        this.validator = validator;
        this.migrations = migrations;
        this.runtimeFactory = runtimeFactory;
        this.registry = registry;
        this.featureRegistry = featureRegistry;
        this.installs = installs;
        this.descriptorParser = descriptorParser;
        this.studioVersion = studioVersion;
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.json = json;
        this.listeners = listeners;
        this.servletContext = servletContext;
        this.events = events;
        this.transactions = new TransactionTemplate(transactionManager);
        this.properties = properties;
        this.restart = restart;
    }

    // ---- SmartLifecycle: boot, shutdown, safe mode (design.md §2, task 6.8) -----------------------

    /**
     * Whether this boot is running with no plugin started, and why — {@code null} once boot has
     * decided otherwise. The manifest (task 6.9) surfaces this so the admin banner can explain it.
     */
    public boolean safeMode() {
        return safeMode;
    }

    public Optional<String> safeModeReason() {
        return Optional.ofNullable(safeModeReason);
    }

    /**
     * Runs after {@code ApplicationReadyEvent}, never during context refresh — Studio is already
     * serving its built-in features by the time this runs, so a plugin failure here can never keep
     * Studio from starting.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        // Off the event thread: readiness (ReadinessState.ACCEPTING_TRAFFIC) is published after
        // every ApplicationReadyEvent listener returns, and must not wait for plugins to start.
        Thread.ofVirtual().name("plugin-boot").start(this::runStartupSequence);
    }

    /**
     * The boot sequence itself, public so a test can trigger it directly instead of waiting for a
     * real {@code ApplicationReadyEvent} (this session's tests seed {@code studio_boot} rows first
     * and then call this to prove the crash-loop and safe-mode decisions).
     */
    public void runStartupSequence() {
        busy.acquireUninterruptibly();
        try {
            startup();
        } finally {
            busy.release();
        }
    }

    private void startup() {
        jdbc.update("DELETE FROM studio_boot WHERE started_at < now() - interval '1 day'");
        long bootId =
                jdbc.queryForObject("INSERT INTO studio_boot (started_at) VALUES (now()) RETURNING id", Long.class);
        currentBootId = bootId;

        boolean forced = properties.safeMode();
        boolean crashLoop = !forced && isCrashLoop(bootId);
        if (forced || crashLoop) {
            safeMode = true;
            safeModeReason = forced
                    ? "Safe mode is forced by artemis-studio.plugins.safe-mode=true."
                    : "Studio stopped uncleanly %d times in the last %d minutes; no plugin was started."
                            .formatted(CRASH_LOOP_BOOT_COUNT, CRASH_LOOP_WINDOW.toMinutes());
            log.warn("plugin-boot id={} safeMode=true reason=\"{}\"", bootId, safeModeReason);
            return;
        }
        safeMode = false;
        safeModeReason = null;

        failStaleActivatingRows();
        checkCompatibility();
        startActiveRowsAtBoot();
    }

    private boolean isCrashLoop(long bootId) {
        List<java.sql.Timestamp> stoppedAts = jdbc.query(
                """
                SELECT stopped_at FROM studio_boot
                WHERE id <> ? AND started_at > now() - ?::interval
                ORDER BY started_at DESC LIMIT ?
                """,
                (rs, rowNum) -> rs.getTimestamp("stopped_at"),
                bootId,
                CRASH_LOOP_WINDOW.toMinutes() + " minutes",
                CRASH_LOOP_BOOT_COUNT);
        return stoppedAts.size() == CRASH_LOOP_BOOT_COUNT && stoppedAts.stream().allMatch(java.util.Objects::isNull);
    }

    /** design.md §4: an {@code activating} row survives only if something else still holds its
     * advisory lock — otherwise the process that was activating it died, and the row is stale. */
    private void failStaleActivatingRows() {
        for (PluginInstallEntity e : installs.findAll()) {
            if (e.status() != PluginInstallStatus.ACTIVATING) {
                continue;
            }
            if (tryAdvisoryLock(e.getId())) {
                store.update(e.getId(), row -> row.fail("Studio stopped while activating"));
                notifyListeners(
                        e.getId(), e.getVersion(), e.getVersion(), e.getSha256(), SYSTEM_ACTOR, "boot", "failed");
            }
        }
    }

    /** {@code true} once the lock was actually acquired (and immediately released) — nothing else
     * currently holds it, so a prior activation attempt for this id died without releasing it. */
    private boolean tryAdvisoryLock(String pluginId) {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement ps = connection.prepareStatement("SELECT pg_try_advisory_lock(hashtext(?))")) {
                ps.setString(1, "plugin:" + pluginId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    boolean acquired = rs.getBoolean(1);
                    if (acquired) {
                        try (PreparedStatement unlock =
                                connection.prepareStatement("SELECT pg_advisory_unlock(hashtext(?))")) {
                            unlock.setString(1, "plugin:" + pluginId);
                            unlock.execute();
                        }
                    }
                    return acquired;
                }
            }
        } catch (SQLException e) {
            log.warn("Could not check the activation lock for plugin '{}' at boot", pluginId, e);
            return false;
        }
    }

    /**
     * Marks every row that would start at boot but whose {@code since..until} no longer covers the
     * running Studio {@code incompatible}, and puts an {@code incompatible} row whose range covers it
     * again (Studio was moved back, or the range was always open-ended) back to {@code active}, so
     * it starts below instead of staying down for good.
     */
    private void checkCompatibility() {
        for (PluginInstallEntity e : installs.findAll()) {
            PluginInstallStatus status = e.status();
            if (!STARTS_AT_BOOT.contains(status) && status != PluginInstallStatus.INCOMPATIBLE) {
                continue;
            }
            PluginDescriptor descriptor;
            try {
                descriptor = parseStoredDescriptor(e);
            } catch (RuntimeException corrupt) {
                store.update(e.getId(), row -> row.fail("Stored descriptor unreadable: " + corrupt.getMessage()));
                continue;
            }
            StudioVersion.Compatibility compat = studioVersion.check(
                    descriptor.studio().since(), descriptor.studio().until());
            boolean outside =
                    compat == StudioVersion.Compatibility.TOO_OLD || compat == StudioVersion.Compatibility.TOO_NEW;
            if (outside && status != PluginInstallStatus.INCOMPATIBLE) {
                String reason = "Studio %s is outside the range this plugin supports (%s..%s)."
                        .formatted(
                                studioVersion.current().map(Object::toString).orElse("?"),
                                descriptor.studio().since(),
                                descriptor.studio().until() == null
                                        ? ""
                                        : descriptor.studio().until());
                store.update(e.getId(), row -> row.incompatible(reason));
                notifyListeners(
                        e.getId(), e.getVersion(), e.getVersion(), e.getSha256(), SYSTEM_ACTOR, "boot", "incompatible");
            } else if (!outside && status == PluginInstallStatus.INCOMPATIBLE) {
                store.update(e.getId(), row -> row.transitionTo(PluginInstallStatus.ACTIVE));
            }
        }
    }

    /**
     * The statuses a boot starts: {@code active}, and {@code needs_restart} — a restart-class
     * activation, a boot start that timed out, or a runtime that did not close cleanly, each of
     * which this restart is the fix for.
     */
    private static final Set<PluginInstallStatus> STARTS_AT_BOOT =
            Set.of(PluginInstallStatus.ACTIVE, PluginInstallStatus.NEEDS_RESTART);

    /**
     * Starts every remaining {@code active} row, dependency-first (a plugin's {@code requires} on
     * another plugin in this same boot starts first), each bounded by {@code
     * artemis-studio.plugins.start-timeout-seconds} (default 60 s) so one slow or hung plugin cannot delay
     * the rest. Studio is already serving before this runs, so a failure here never blocks it.
     */
    private void startActiveRowsAtBoot() {
        List<PluginInstallEntity> rows = installs.findAll().stream()
                .filter(e -> STARTS_AT_BOOT.contains(e.status()))
                .toList();
        Map<String, PluginDescriptor> descriptors = new LinkedHashMap<>();
        for (PluginInstallEntity e : rows) {
            try {
                descriptors.put(e.getId(), parseStoredDescriptor(e));
            } catch (RuntimeException corrupt) {
                store.update(e.getId(), row -> row.fail("Stored descriptor unreadable: " + corrupt.getMessage()));
            }
        }
        Duration timeout = Duration.ofSeconds(properties.startTimeoutSeconds());
        // A thread per start, never an ExecutorService: closing one waits for every task, so a
        // plugin that hangs past its timeout would hang the boot sequence with it.
        Executor executor = task -> Thread.ofVirtual().name("plugin-start").start(task);
        for (String id : startOrder(descriptors)) {
            startOneAtBoot(id, descriptors.get(id), timeout, executor);
        }
    }

    /** Dependency-first order: a plugin's {@code requires} on another plugin being started here
     * starts first. Best-effort against a requires cycle — it just starts in whatever order the
     * recursion allows rather than looping forever. */
    private List<String> startOrder(Map<String, PluginDescriptor> descriptors) {
        List<String> order = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        for (String id : descriptors.keySet()) {
            visitForOrder(id, descriptors, visited, visiting, order);
        }
        return order;
    }

    private void visitForOrder(
            String id,
            Map<String, PluginDescriptor> descriptors,
            Set<String> visited,
            Set<String> visiting,
            List<String> order) {
        if (visited.contains(id) || !descriptors.containsKey(id) || !visiting.add(id)) {
            return;
        }
        for (String dependency : descriptors.get(id).requires()) {
            visitForOrder(dependency, descriptors, visited, visiting, order);
        }
        visited.add(id);
        order.add(id);
    }

    private void startOneAtBoot(String id, PluginDescriptor descriptor, Duration timeout, Executor executor) {
        if (descriptor == null) {
            return; // its descriptor failed to parse above; already marked failed.
        }
        Path jarPath;
        try {
            jarPath = store.materialize(installs.findById(id).orElseThrow().getSha256());
        } catch (Exception e) {
            store.update(id, row -> row.fail("Artifact unreadable: " + e.getMessage()));
            notifyListeners(id, descriptor.version(), descriptor.version(), null, SYSTEM_ACTOR, "boot-start", "failed");
            return;
        }
        CompletableFuture<PluginRuntime> future = CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return runtimeFactory.activate(descriptor, jarPath, servletContext);
                    } catch (Exception e) {
                        throw new CompletionException(e);
                    }
                },
                executor);
        try {
            PluginRuntime runtime = future.get(timeout.toSeconds(), TimeUnit.SECONDS);
            if (!running) {
                closeRuntime(runtime); // Studio began shutting down while this plugin was starting
                return;
            }
            registry.set(id, new Active(runtime));
            store.update(id, row -> row.transitionTo(PluginInstallStatus.ACTIVE));
            notifyListeners(
                    id, descriptor.version(), descriptor.version(), null, SYSTEM_ACTOR, "boot-start", "succeeded");
        } catch (TimeoutException timedOut) {
            String reason = "Plugin '%s' did not start within %ds".formatted(id, timeout.toSeconds());
            store.update(id, row -> row.needsRestart(reason));
            notifyListeners(id, descriptor.version(), descriptor.version(), null, SYSTEM_ACTOR, "boot-start", "failed");
            // ponytail: interrupting a virtual thread mid-Spring-refresh does not actually stop it;
            // if it finishes late anyway, close the orphaned runtime instead of leaking it.
            future.whenComplete((runtime, error) -> {
                if (runtime != null) {
                    log.warn("Plugin '{}' finished starting after its boot timeout; closing it.", id);
                    closeRuntime(runtime);
                }
            });
        } catch (ExecutionException | CompletionException failed) {
            Throwable cause = failed.getCause() != null ? failed.getCause() : failed;
            store.update(id, row -> row.fail(describeFailure(cause)));
            notifyListeners(id, descriptor.version(), descriptor.version(), null, SYSTEM_ACTOR, "boot-start", "failed");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void start() {
        // Spring calls this during context refresh (SmartLifecycle's own auto-startup) — the boot
        // sequence itself must never run here (design.md §2), only after ApplicationReadyEvent.
        running = true;
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        for (String id : registry.activeIds()) {
            activeRuntime(id).ifPresent(runtime -> {
                runtime.close();
                if (runtime.stuck()) {
                    store.update(
                            id,
                            row -> row.needsRestart(
                                    "Plugin '" + id + "' did not close within 10s while Studio was shutting down."));
                }
                registry.remove(id);
            });
        }
        Long bootId = currentBootId;
        if (bootId != null) {
            jdbc.update("UPDATE studio_boot SET stopped_at = now() WHERE id = ?", bootId);
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return ShutdownPhases.PLUGINS;
    }

    private void notifyListeners(
            String id, String from, String to, String sha256, String actor, String step, String outcome) {
        log.info(
                "plugin-lifecycle id={} from={} to={} sha256={} actor={} step={} outcome={}",
                id,
                from,
                to,
                sha256,
                actor,
                step,
                outcome);
        listeners.forEach(l -> {
            try {
                l.onStep(id, from, to, sha256, actor, step, outcome);
            } catch (RuntimeException e) {
                log.warn("Plugin lifecycle listener {} threw for plugin '{}'", l, id, e);
            }
        });
    }

    // ---- unload watch (design.md §2) ------------------------------------------------------------

    /** A closed runtime's class loader, weakly held: once collected, the version truly unloaded. */
    private record Closed(String label, WeakReference<ClassLoader> loader, Instant closedAt) {}

    private final List<Closed> closed = new CopyOnWriteArrayList<>();
    private volatile Instant lastUnloadCheck = Instant.EPOCH;

    private static final Duration UNLOAD_GRACE = Duration.ofMinutes(1);
    private static final Duration UNLOAD_CHECK_INTERVAL = Duration.ofMinutes(10);

    private void closeRuntime(PluginRuntime runtime) {
        runtime.close();
        closed.add(new Closed(
                runtime.id() + " " + runtime.descriptor().version(),
                new WeakReference<>(runtime.classLoader()),
                Instant.now()));
    }

    /**
     * Plugin versions closed more than a minute ago whose classes are still in memory — something
     * still references them, and only a restart frees that memory. Asks the JVM for a collection
     * at most every ten minutes, and only while there is such a candidate, since a quiet Studio may
     * otherwise not unload classes for a long time.
     */
    public List<String> unreleased() {
        closed.removeIf(c -> c.loader().get() == null);
        Instant cutoff = Instant.now().minus(UNLOAD_GRACE);
        if (closed.stream().anyMatch(c -> c.closedAt().isBefore(cutoff))
                && lastUnloadCheck.isBefore(Instant.now().minus(UNLOAD_CHECK_INTERVAL))) {
            lastUnloadCheck = Instant.now();
            System.gc();
            closed.removeIf(c -> c.loader().get() == null);
        }
        return closed.stream()
                .filter(c -> c.closedAt().isBefore(cutoff))
                .map(Closed::label)
                .toList();
    }

    // ---- read side --------------------------------------------------------------------------

    public List<PluginSummary> list() {
        return installs.findAll().stream().map(this::toSummary).toList();
    }

    public Optional<PluginSummary> status(String id) {
        return installs.findById(id).map(this::toSummary);
    }

    private PluginSummary toSummary(PluginInstallEntity e) {
        boolean stuck = activeRuntime(e.getId()).map(PluginRuntime::stuck).orElse(false);
        return new PluginSummary(
                e.getId(),
                e.getVersion(),
                e.getVendor(),
                e.getSha256(),
                e.getPreviousSha256(),
                e.status(),
                e.getFailure(),
                e.getProgress(),
                e.getStepStartedAt(),
                e.getInstalledAt(),
                e.getActivatedAt(),
                e.getInstalledBy(),
                stuck,
                e.getPreviousSha256() != null && !e.isSchemaChanged() && e.status() != PluginInstallStatus.UNINSTALLED,
                tryParseStoredDescriptor(e));
    }

    private PluginDescriptor tryParseStoredDescriptor(PluginInstallEntity e) {
        try {
            return parseStoredDescriptor(e);
        } catch (RuntimeException corrupt) {
            return null;
        }
    }

    // ---- upload -----------------------------------------------------------------------------------

    /** An inspected upload: stored and planned, installed only once someone activates it. */
    public record Inspection(String sha256, ActivationPlan plan, List<Violation> warnings) {}

    /** Uploads nobody activated within a day are forgotten, and their artifacts removed. */
    private static final String EXPIRE_UPLOADS =
            "DELETE FROM plugin_upload WHERE uploaded_at < now() - interval '1 day'";

    /**
     * Validates {@code jar}, and only when it is valid stores it as an inert upload and plans what
     * activating it would do (design.md §6/§7). No plugin code runs and nothing is installed.
     * A plan the host refuses (a vendor mismatch, a downgrade) forgets the upload again, so an
     * artifact is only ever kept when it could be activated.
     */
    public Inspection inspect(Path jar, String actor) throws IOException {
        jdbc.update(EXPIRE_UPLOADS);
        store.garbageCollect();
        ValidationReport report =
                validator.validate(jar, otherBasePackages(peekId(jar).orElse(null)));
        if (!report.valid()) {
            throw new PluginRefusedException(report.violations());
        }
        String sha256 = store.put(java.nio.file.Files.readAllBytes(jar));
        jdbc.update(
                """
                INSERT INTO plugin_upload (uploaded_at, sha256, plugin_id, uploaded_by, descriptor, report)
                VALUES (now(), ?, ?, ?, ?::jsonb, ?::jsonb)
                ON CONFLICT (sha256) DO UPDATE SET uploaded_at = now(), uploaded_by = EXCLUDED.uploaded_by
                """,
                sha256,
                report.descriptor().id(),
                actor,
                json.writeValueAsString(report.descriptor()),
                json.writeValueAsString(Map.of("warnings", report.warnings())));
        try {
            return new Inspection(sha256, buildPlan(sha256, false).plan(), report.warnings());
        } catch (RuntimeException refused) {
            forgetUpload(sha256);
            throw refused;
        }
    }

    /** Whether {@code sha256} is an upload still waiting to be activated. */
    public boolean isPendingUpload(String sha256) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM plugin_upload WHERE sha256 = ? AND uploaded_at >= now() - interval '1 day')",
                Boolean.class,
                sha256));
    }

    /** The plugin a pending upload would install or update. */
    public Optional<String> uploadPluginId(String sha256) {
        return jdbc.queryForList("SELECT plugin_id FROM plugin_upload WHERE sha256 = ?", String.class, sha256).stream()
                .findFirst();
    }

    /** What activating a pending upload would do, recomputed now. */
    public ActivationPlan planUpload(String sha256) {
        requirePendingUpload(sha256);
        return plan(sha256);
    }

    /** Activates a pending upload: an install, or an update of the plugin it names. */
    public ActivationPlan activateUpload(String sha256, String actor) {
        requirePendingUpload(sha256);
        return activate(sha256, actor);
    }

    private void requirePendingUpload(String sha256) {
        if (!isPendingUpload(sha256)) {
            throw new PluginRefusedException(List.of(new Violation(
                    "not-found",
                    "No pending upload " + sha256 + "; uploads not activated within a day are removed.",
                    "Upload the jar again.")));
        }
    }

    /** Drops an upload nobody will activate, and its artifact unless an install still uses it. */
    public void forgetUpload(String sha256) {
        jdbc.update("DELETE FROM plugin_upload WHERE sha256 = ?", sha256);
        store.garbageCollect();
    }

    // ---- connection budget ----------------------------------------------------------------------

    /**
     * design.md §2: 10 core connections plus 3 per active plugin, against 80% of Postgres'
     * {@code max_connections}.
     *
     * @param inUse what the active plugins need now, core included
     * @param limit the most activation may reach
     */
    public record ConnectionBudget(int maxConnections, int inUse, int limit, int perPlugin) {}

    public ConnectionBudget connectionBudget() {
        int max = readMaxConnections();
        int active = (int) installs.countByStatus(PluginInstallStatus.ACTIVE.dbValue());
        return new ConnectionBudget(
                max, CORE_CONNECTIONS + POOL_PER_PLUGIN * active, (int) (max * 0.8), POOL_PER_PLUGIN);
    }

    private static final int CORE_CONNECTIONS = 10;
    private static final int POOL_PER_PLUGIN = 3;

    // ---- plan ---------------------------------------------------------------------------------

    /** What activating {@code sha256} would do, computed without changing anything — in particular,
     * never creates the plugin's schema; that is {@link PluginMigrations#migrate}'s job, inside the
     * advisory-locked activation sequence (design.md §4). */
    public ActivationPlan plan(String sha256) {
        return buildPlan(sha256, false).plan();
    }

    // ---- activate -------------------------------------------------------------------------------

    /**
     * Refuses synchronously (a missing {@code requires}, the connection budget — everything else
     * {@link #plan(String)} already refuses), then persists the {@code activating} transition and
     * hands the rest to a virtual thread. Returns the same plan {@link #plan(String)} would have.
     */
    public ActivationPlan activate(String sha256, String actor) {
        return activateSha(sha256, actor, false);
    }

    private ActivationPlan activateSha(String sha256, String actor, boolean allowDowngrade) {
        acquire();
        boolean handedOff = false;
        try {
            ActivationPlan plan = beginActivation(sha256, actor, allowDowngrade);
            handedOff = true;
            return plan;
        } finally {
            if (!handedOff) {
                busy.release();
            }
        }
    }

    /** Runs with {@link #busy} held; once it returns normally, the activation thread owns the permit. */
    private ActivationPlan beginActivation(String sha256, String actor, boolean allowDowngrade) {
        PlanContext ctx = buildPlan(sha256, allowDowngrade);
        ActivationPlan plan = ctx.plan();
        if (!plan.missingRequires().isEmpty()) {
            throw new PluginRefusedException(List.of(new Violation(
                    "requires-missing",
                    "Plugin '%s' requires %s, which is not active.".formatted(plan.pluginId(), plan.missingRequires()),
                    "Install and activate the required plugin, or enable the required feature, first.")));
        }
        checkConnectionBudget(plan.pluginId());

        String id = plan.pluginId();
        String descriptorJson = json.writeValueAsString(ctx.descriptor());
        boolean fresh = ctx.previousDescriptor() == null;
        if (fresh) {
            store.beginInstall(ctx.descriptor(), sha256, descriptorJson, actor);
        } else {
            store.update(id, e -> e.transitionTo(PluginInstallStatus.ACTIVATING));
        }
        step(ctx, actor, "validating");

        Thread.ofVirtual().name("plugin-activate-" + id).start(() -> {
            try {
                runActivation(ctx, actor, sha256, descriptorJson, fresh);
            } finally {
                busy.release();
            }
        });
        return plan;
    }

    private void runActivation(PlanContext ctx, String actor, String sha256, String descriptorJson, boolean fresh) {
        String id = ctx.descriptor().id();
        try {
            switch (ctx.plan().activationClass()) {
                case RESTART -> {
                    // Record the version to start, so the restart this asks for starts it. The
                    // running runtime (if any) keeps serving the previous version until then.
                    boolean schemaChanged = !ctx.plan().pendingChangesets().isEmpty();
                    store.update(id, e -> {
                        if (!sha256.equals(e.getSha256())) {
                            e.update(ctx.plan().toVersion(), sha256, descriptorJson, schemaChanged);
                        }
                        e.needsRestart("Restart Studio to start %s %s."
                                .formatted(id, ctx.plan().toVersion()));
                    });
                    store.consumeUpload(sha256);
                    logStep(ctx, actor, "needs_restart", "needs-restart");
                    // The installer confirmed a plan that said Studio restarts itself; the next
                    // boot starts this version (needs_restart rows start at boot).
                    if (ctx.plan().restart() == ActivationPlan.Restart.AUTOMATIC) {
                        restart.restart(
                                "%s %s needs a restart".formatted(id, ctx.plan().toVersion()));
                    }
                }
                case INSTANT -> activateInstant(ctx, actor, sha256, descriptorJson, fresh);
                case BRIEF_MAINTENANCE -> activateBriefMaintenance(ctx, actor, sha256, descriptorJson, fresh);
            }
        } catch (Exception | Error e) {
            log.error("Plugin '{}' activation crashed", id, e);
            try {
                store.fail(id, describeFailure(e));
            } catch (RuntimeException ignored) {
                // the row may already be gone (a concurrent purge); the failure is still logged above
            }
            logStep(ctx, actor, "failed", "failed");
        }
    }

    private void activateInstant(PlanContext ctx, String actor, String sha256, String descriptorJson, boolean fresh) {
        String id = ctx.descriptor().id();
        PluginRuntime newRuntime;
        try {
            // No changeset is pending, so "migrating" only takes the lock and confirms that.
            newRuntime = runtimeFactory.activate(
                    ctx.descriptor(), ctx.jarPath(), servletContext, name -> step(ctx, actor, name));
        } catch (Exception | Error buildFailure) {
            // The old runtime was never touched: it keeps serving, and the row keeps recording
            // the version it is actually running (design.md §5's Instant failure case).
            store.fail(id, describeFailure(buildFailure));
            logStep(ctx, actor, "failed", "failed");
            return;
        }
        step(ctx, actor, "registering");
        Optional<PluginRuntime> old = activeRuntime(id);
        registry.set(id, new Active(newRuntime));
        old.ifPresent(this::closeRuntime);
        finishSuccess(ctx, actor, sha256, descriptorJson, fresh);
    }

    private void activateBriefMaintenance(
            PlanContext ctx, String actor, String sha256, String descriptorJson, boolean fresh) {
        String id = ctx.descriptor().id();
        // Captured before the registry slot is overwritten below: activeRuntime(id) reads the
        // CURRENT slot, and once it holds Updating there is no Active runtime left to find — every
        // Brief-maintenance activation would then think it had no old version to close or, on
        // failure, to resume, silently leaking the old runtime's pool/context/classloader and
        // breaking design.md §5's "Migration fails ... the old version resumes".
        Optional<PluginRuntime> old = activeRuntime(id);
        registry.set(id, new PluginRuntimeRegistry.Updating(BRIEF_MAINTENANCE_RETRY_SECONDS));
        step(ctx, actor, "draining");
        old.ifPresent(this::closeRuntime);

        PluginRuntime newRuntime;
        try {
            newRuntime = runtimeFactory.activate(
                    ctx.descriptor(), ctx.jarPath(), servletContext, name -> step(ctx, actor, name));
        } catch (Exception | Error failure) {
            handleBriefMaintenanceFailure(ctx, actor, old.isPresent(), failure);
            return;
        }
        step(ctx, actor, "registering");
        registry.set(id, new Active(newRuntime));
        finishSuccess(ctx, actor, sha256, descriptorJson, fresh);
    }

    private void finishSuccess(PlanContext ctx, String actor, String sha256, String descriptorJson, boolean fresh) {
        String id = ctx.descriptor().id();
        boolean schemaChanged = !ctx.plan().pendingChangesets().isEmpty();
        if (fresh) {
            store.update(id, e -> {
                e.schemaChanged(schemaChanged);
                e.transitionTo(PluginInstallStatus.ACTIVE);
            });
        } else {
            store.update(id, e -> {
                // Re-enabling the same artifact is not an update: previous_sha256 keeps naming the
                // version before it, so Roll back still has somewhere to go.
                if (!sha256.equals(e.getSha256())) {
                    e.update(ctx.plan().toVersion(), sha256, descriptorJson, schemaChanged);
                } else if (schemaChanged) {
                    e.schemaChanged(true);
                }
                e.transitionTo(PluginInstallStatus.ACTIVE);
            });
        }
        store.consumeUpload(sha256);
        logStep(ctx, actor, "active", "succeeded");
    }

    /**
     * design.md §5's Brief-maintenance failure cases: a migration/confinement failure leaves the
     * old version's schema state mostly intact (Liquibase's own per-changeset transaction rolls
     * the failing changeset back, and a confinement violation is already rolled back to the tag by
     * {@link PluginMigrations} itself), so the old version simply resumes. A failure <em>after</em>
     * migrating (the new context failed to start) resumes the old version only if every applied
     * changeset is reversible — rolling the schema back to the tag first; otherwise nothing resumes,
     * because the schema is now ahead of what the old code expects.
     */
    private void handleBriefMaintenanceFailure(PlanContext ctx, String actor, boolean hadOld, Throwable cause) {
        String id = ctx.descriptor().id();
        if (cause instanceof PluginMigrationException) {
            resumeOld(ctx, actor, hadOld, cause);
            return;
        }
        boolean reversible;
        try {
            reversible = migrations.isReversible(ctx.jarPath(), id);
        } catch (Exception e) {
            reversible = false;
        }
        if (reversible) {
            try {
                migrations.rollbackToTag(dataSource, schemaName(id), tagFor(ctx.descriptor()), ctx.jarPath(), id);
                resumeOld(ctx, actor, hadOld, cause);
                return;
            } catch (Exception rollbackFailure) {
                store.fail(
                        id,
                        "Activation failed (%s); the schema could not be rolled back either: %s"
                                .formatted(describeFailure(cause), describeFailure(rollbackFailure)));
                registry.remove(id);
                logStep(ctx, actor, "failed", "failed");
                return;
            }
        }
        store.fail(
                id,
                "Activation failed after the schema was migrated to %s: %s"
                        .formatted(ctx.descriptor().version(), describeFailure(cause)));
        registry.remove(id);
        logStep(ctx, actor, "failed", "failed");
    }

    private void resumeOld(PlanContext ctx, String actor, boolean hadOld, Throwable cause) {
        String id = ctx.descriptor().id();
        store.fail(id, describeFailure(cause));
        if (!hadOld || ctx.previousDescriptor() == null || ctx.previousSha() == null) {
            registry.remove(id);
            logStep(ctx, actor, "failed", "failed");
            return;
        }
        try {
            Path oldJar = store.materialize(ctx.previousSha());
            PluginRuntime resumed = runtimeFactory.activate(ctx.previousDescriptor(), oldJar, servletContext);
            registry.set(id, new Active(resumed));
        } catch (Exception resumeFailure) {
            log.error(
                    "Plugin '{}' could not resume its previous version {} after a failed update",
                    id,
                    ctx.previousDescriptor().version(),
                    resumeFailure);
            registry.remove(id);
        }
        logStep(ctx, actor, "failed", "failed");
    }

    // ---- disable / enable / uninstall ------------------------------------------------------------

    /**
     * Every currently active plugin whose descriptor {@code requires} {@code id} — design.md §5's
     * "disabling a plugin that others require": the dialog lists these before confirmation, and
     * {@link #disable(String, boolean, String)}/{@link #uninstall(String, boolean, String)} refuse
     * without {@code cascade} while this is non-empty.
     */
    public List<String> dependantsOf(String id) {
        return installs.findAll().stream()
                .filter(e ->
                        e.status() == PluginInstallStatus.ACTIVE && !e.getId().equals(id))
                .filter(e -> parseStoredDescriptor(e).requires().contains(id))
                .map(PluginInstallEntity::getId)
                .toList();
    }

    /**
     * Drains and closes the running plugin (if any — {@link PluginRuntime#close()} detaches every
     * {@link io.github.sudoitir.artemisstudio.kernel.plugin.PluginBridge}, including {@link
     * FeatureRegistry}, so the gateway answers 404 {@code feature-disabled} as soon as this
     * returns), then marks the row {@code disabled}. Refuses, naming the dependants, unless {@code
     * cascade} is set — which disables every dependant first, recursively.
     */
    public void disable(String id, boolean cascade, String actor) {
        exclusively(() -> transitionOut(id, cascade, actor, PluginInstallStatus.DISABLED));
    }

    /** {@link #disable(String, boolean, String)} plus {@code uninstalled} instead of {@code disabled}; data is kept. */
    public void uninstall(String id, boolean cascade, String actor) {
        exclusively(() -> transitionOut(id, cascade, actor, PluginInstallStatus.UNINSTALLED));
    }

    /**
     * Takes {@link #busy}, waiting a few seconds for an operation that is just finishing (a caller
     * polling for {@code active} can see it a moment before the activation thread lets go), and
     * refuses with {@code lifecycle-busy} otherwise.
     */
    private void acquire() {
        boolean acquired;
        try {
            acquired = busy.tryAcquire(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        if (!acquired) {
            throw new PluginRefusedException(List.of(new Violation(
                    "lifecycle-busy",
                    "Another plugin operation is in progress.",
                    "Wait for it to finish, then try again.")));
        }
    }

    /** Package-private so a test can hold {@link #busy} across a second call. */
    void exclusively(Runnable operation) {
        acquire();
        try {
            operation.run();
        } finally {
            busy.release();
        }
    }

    private void transitionOut(String id, boolean cascade, String actor, PluginInstallStatus finalStatus) {
        requireInstall(id);
        List<String> dependants = dependantsOf(id);
        if (!dependants.isEmpty() && !cascade) {
            throw new PluginRefusedException(List.of(new Violation(
                    "requires-dependants",
                    "Plugin '%s' is required by %s.".formatted(id, dependants),
                    "Disable them together, or disable each of them first.")));
        }
        if (cascade) {
            for (String dependant : dependants) {
                transitionOut(dependant, true, actor, PluginInstallStatus.DISABLED);
            }
        }
        activeRuntime(id).ifPresent(this::closeRuntime);
        registry.remove(id);
        store.update(id, e -> e.transitionTo(finalStatus));
        PluginInstallEntity entity = requireInstall(id);
        log.info(
                "plugin-lifecycle id={} from={} to={} sha256={} actor={} step={} outcome=succeeded",
                id,
                entity.getVersion(),
                entity.getVersion(),
                entity.getSha256(),
                actor,
                finalStatus.dbValue());
        listeners.forEach(l -> {
            try {
                l.onStep(
                        id,
                        entity.getVersion(),
                        entity.getVersion(),
                        entity.getSha256(),
                        actor,
                        finalStatus.dbValue(),
                        "succeeded");
            } catch (RuntimeException e) {
                log.warn("Plugin lifecycle listener {} threw for plugin '{}'", l, id, e);
            }
        });
    }

    /** Starts the plugin again from its current sha (design.md §5): Instant, or Brief maintenance if its schema has pending changesets. */
    public ActivationPlan enable(String id, String actor) {
        PluginInstallEntity entity = requireInstall(id);
        if (entity.status() == PluginInstallStatus.ACTIVE || entity.status() == PluginInstallStatus.ACTIVATING) {
            throw new PluginRefusedException(List.of(new Violation(
                    "already-active",
                    "Plugin '%s' is already %s.".formatted(id, entity.status().dbValue()),
                    "")));
        }
        return activateSha(entity.getSha256(), actor, false);
    }

    // ---- rollback ---------------------------------------------------------------------------------

    /**
     * Reactivates the previous version's own jar, Instant, only when it is still on record and the
     * current version applied no changeset (design.md §5/§6.4 — "reversible" here means the schema,
     * not the data). Otherwise refuses, naming the fix.
     */
    public ActivationPlan rollback(String id, String actor) {
        PluginInstallEntity entity = requireInstall(id);
        if (entity.status() == PluginInstallStatus.UNINSTALLED) {
            throw new PluginRefusedException(List.of(new Violation(
                    "rollback-unavailable",
                    "Plugin '%s' is uninstalled.".formatted(id),
                    "Upload the version you want to install.")));
        }
        if (entity.getPreviousSha256() == null || entity.isSchemaChanged()) {
            throw new PluginRefusedException(List.of(new Violation(
                    "rollback-unavailable",
                    "Plugin '%s' cannot be rolled back: the current version changed the database.".formatted(id),
                    "Upload a fixed version, or restore from backup.")));
        }
        return activateSha(entity.getPreviousSha256(), actor, true);
    }

    // ---- purge --------------------------------------------------------------------------------------

    /** design.md §4: a dry-run estimate of what {@link #purge(String, String)} would remove. */
    public PurgePlan purgePlan(String id) {
        requireInstall(id);
        String schema = schemaName(id);
        List<PurgePlan.TableEstimate> tables = jdbc.query(
                """
                SELECT c.relname, c.reltuples::bigint, pg_total_relation_size(c.oid)
                FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = ? AND c.relkind = 'r'
                ORDER BY c.relname
                """,
                (rs, rowNum) -> new PurgePlan.TableEstimate(rs.getString(1), rs.getLong(2), rs.getLong(3)),
                schema);
        Long grants =
                jdbc.queryForObject("SELECT count(*) FROM role_permission WHERE action LIKE ?", Long.class, id + ":%");
        Long settings =
                jdbc.queryForObject("SELECT count(*) FROM studio_setting WHERE key LIKE ?", Long.class, id + ".%");
        PluginInstallEntity entity = requireInstall(id);
        long artifacts = java.util.stream.Stream.of(entity.getSha256(), entity.getPreviousSha256())
                .filter(java.util.Objects::nonNull)
                .distinct()
                .count();
        return new PurgePlan(schema, tables, grants == null ? 0 : grants, settings == null ? 0 : settings, artifacts);
    }

    /**
     * design.md §4: only once {@code uninstalled}. Drops the plugin's schema, its {@code
     * role_permission} and {@code studio_setting} rows and its {@code plugin_install} row in one
     * transaction — {@code api_token_grant} rows go through {@link PluginPurged}, published inside
     * that same transaction, since {@code kernel.plugin} may not depend on {@code
     * feature.apitokens} — then garbage-collects any artifact the row was the last reference to.
     */
    public void purge(String id, String actor) {
        exclusively(() -> purgeUninstalled(id, actor));
    }

    private void purgeUninstalled(String id, String actor) {
        PluginInstallEntity before = requireInstall(id);
        if (before.status() != PluginInstallStatus.UNINSTALLED) {
            throw new PluginRefusedException(List.of(new Violation(
                    "purge-requires-uninstalled",
                    "Plugin '%s' must be uninstalled before it can be purged (currently %s)."
                            .formatted(id, before.status().dbValue()),
                    "Uninstall the plugin first.")));
        }
        String schema = schemaName(id);
        String version = before.getVersion();
        String sha256 = before.getSha256();
        transactions.executeWithoutResult(status -> {
            jdbc.execute("DROP SCHEMA IF EXISTS " + quoteIdent(schema) + " CASCADE");
            jdbc.update("DELETE FROM role_permission WHERE action LIKE ?", id + ":%");
            events.publishEvent(new PluginPurged(id));
            jdbc.update("DELETE FROM studio_setting WHERE key LIKE ?", id + ".%");
            installs.deleteById(id);
        });
        store.garbageCollect();
        log.info(
                "plugin-lifecycle id={} from={} to={} sha256={} actor={} step=purged outcome=succeeded",
                id,
                version,
                version,
                sha256,
                actor);
        listeners.forEach(l -> {
            try {
                l.onStep(id, version, version, sha256, actor, "purged", "succeeded");
            } catch (RuntimeException e) {
                log.warn("Plugin lifecycle listener {} threw for plugin '{}'", l, id, e);
            }
        });
    }

    private PluginInstallEntity requireInstall(String id) {
        return installs.findById(id)
                .orElseThrow(() -> new PluginRefusedException(
                        List.of(new Violation("not-found", "No installed plugin '" + id + "'.", ""))));
    }

    // ---- plan computation -----------------------------------------------------------------------

    private static final int BRIEF_MAINTENANCE_RETRY_SECONDS = 15;

    /** {@code jarPath}, both descriptors and the computed {@link ActivationPlan}, threaded from
     * {@link #buildPlan(String, boolean)} into the async activation so it never has to recompute or re-read
     * anything the plan already established — in particular the <em>previous</em> descriptor, which
     * {@link PluginStore#update} would otherwise have already overwritten by the time a failure
     * needs it to resume the old version. */
    private record PlanContext(
            String sha256,
            Path jarPath,
            PluginDescriptor descriptor,
            PluginDescriptor previousDescriptor,
            String previousSha,
            ActivationPlan plan) {}

    private PlanContext buildPlan(String sha256, boolean allowDowngrade) {
        Path jarPath;
        try {
            jarPath = store.materialize(sha256);
        } catch (IOException e) {
            throw new PluginRefusedException(List.of(new Violation(
                    "artifact-unreadable",
                    "The stored artifact for %s could not be materialized: %s".formatted(sha256, e.getMessage()),
                    "Re-upload the jar.")));
        }

        Set<String> otherBasePackages = otherBasePackages(peekId(jarPath).orElse(null));
        ValidationReport report = validator.validate(jarPath, otherBasePackages);
        if (!report.valid()) {
            throw new PluginRefusedException(report.errors());
        }
        PluginDescriptor descriptor = report.descriptor();
        String pluginId = descriptor.id();

        Optional<PluginInstallEntity> existingOpt =
                installs.findById(pluginId).filter(e -> e.status() != PluginInstallStatus.UNINSTALLED);
        PluginDescriptor previousDescriptor =
                existingOpt.map(this::parseStoredDescriptor).orElse(null);
        String previousSha = existingOpt.map(PluginInstallEntity::getSha256).orElse(null);

        // Checked against an uninstalled row too: its schema and data are kept until a purge, so a
        // different vendor's jar, or an older version, would be handed data it did not write.
        installs.findById(pluginId).ifPresent(existing -> {
            boolean uninstalled = existing.status() == PluginInstallStatus.UNINSTALLED;
            if (!existing.getVendor().equals(descriptor.vendor().name())) {
                throw new PluginRefusedException(List.of(new Violation(
                        "vendor-mismatch",
                        "Plugin '%s' is installed from vendor '%s'; this jar is from '%s'."
                                .formatted(
                                        pluginId,
                                        existing.getVendor(),
                                        descriptor.vendor().name()),
                        "Purge the existing installation first, or upload a jar from the same vendor.")));
            }
            if (!allowDowngrade && SemVer.compare(descriptor.version(), existing.getVersion()) < 0) {
                throw new PluginRefusedException(List.of(new Violation(
                        "downgrade-refused",
                        "%s %s is older than the installed %s."
                                .formatted(pluginId, descriptor.version(), existing.getVersion()),
                        uninstalled
                                ? "Its data from %s is kept; purge it first, or upload %s or newer."
                                        .formatted(existing.getVersion(), existing.getVersion())
                                : "Use Roll back instead of uploading an older version.")));
            }
        });

        String schema = schemaName(pluginId);
        // Schema creation belongs to the advisory-locked activation sequence in
        // PluginMigrations.migrate() (design.md §4), never to a read-only plan(): a schema that
        // doesn't exist yet just means Liquibase's own DATABASECHANGELOG lookup comes back empty,
        // which pendingChangesets/updateSql already treat as "every changeset is pending" — exactly
        // what a pre-install review should show, with no side effect on an admin who never installs.
        List<ChangesetInfo> pending;
        String updateSql;
        try {
            pending = migrations.pendingChangesets(dataSource, schema, pluginId, jarPath);
            updateSql = pending.isEmpty() ? "" : migrations.updateSql(dataSource, schema, pluginId, jarPath);
        } catch (Exception e) {
            throw new PluginRefusedException(List.of(new Violation(
                    "changelog-invalid",
                    "The plugin's changelog could not be evaluated: " + e.getMessage(),
                    "Fix the Liquibase changelog and re-upload.")));
        }

        boolean stuckNow = activeRuntime(pluginId).map(PluginRuntime::stuck).orElse(false);
        ActivationClass activationClass = descriptor.activation() == PluginDescriptor.Activation.RESTART || stuckNow
                ? ActivationClass.RESTART
                : !pending.isEmpty() ? ActivationClass.BRIEF_MAINTENANCE : ActivationClass.INSTANT;

        ContributionDiff diff = diff(previousDescriptor, descriptor);
        Map<String, Integer> rolesLosing = rolesLosingPermission(diff.permissionsRemoved());
        List<String> missing = missingRequires(descriptor);
        StudioVersion.Compatibility compat = studioVersion.check(
                descriptor.studio().since(), descriptor.studio().until());
        boolean compatible =
                compat != StudioVersion.Compatibility.TOO_OLD && compat != StudioVersion.Compatibility.TOO_NEW;

        ActivationPlan plan = new ActivationPlan(
                pluginId,
                previousDescriptor == null ? null : previousDescriptor.version(),
                descriptor.version(),
                activationClass,
                pending,
                updateSql,
                diff,
                rolesLosing,
                compatible,
                missing,
                descriptor,
                activationClass != ActivationClass.RESTART
                        ? ActivationPlan.Restart.NONE
                        : restart.supervised() ? ActivationPlan.Restart.AUTOMATIC : ActivationPlan.Restart.MANUAL);
        return new PlanContext(sha256, jarPath, descriptor, previousDescriptor, previousSha, plan);
    }

    /** A cheap, separate parse of just the id — no violations recorded, no validation performed —
     * so the base-package overlap check below can exclude this plugin's own previous row before the
     * real, full {@link PluginValidator#validate} runs. */
    private Optional<String> peekId(Path jarPath) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry entry = jar.getJarEntry("META-INF/artemis-studio/plugin.json");
            if (entry == null) {
                return Optional.empty();
            }
            byte[] bytes = jar.getInputStream(entry).readNBytes(PluginDescriptorParser.MAX_BYTES + 1);
            return Optional.ofNullable(descriptorParser.parse(bytes).id());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private Set<String> otherBasePackages(String excludeId) {
        Set<String> result = new HashSet<>();
        for (PluginInstallEntity e : installs.findAll()) {
            if (e.status() == PluginInstallStatus.UNINSTALLED || e.getId().equals(excludeId)) {
                continue;
            }
            try {
                result.add(parseStoredDescriptor(e).basePackage());
            } catch (RuntimeException corrupt) {
                log.warn(
                        "Stored descriptor for plugin '{}' could not be parsed; skipped in the overlap check",
                        e.getId(),
                        corrupt);
            }
        }
        return result;
    }

    private PluginDescriptor parseStoredDescriptor(PluginInstallEntity entity) {
        try {
            return descriptorParser.parse(entity.getDescriptor().getBytes(StandardCharsets.UTF_8));
        } catch (PluginDescriptorException e) {
            throw new IllegalStateException("Stored descriptor for plugin '" + entity.getId() + "' is unreadable", e);
        }
    }

    private ContributionDiff diff(PluginDescriptor oldD, PluginDescriptor newD) {
        Set<String> oldPerms = oldD == null
                ? Set.of()
                : oldD.permissions().stream()
                        .map(PluginDescriptor.Permission::action)
                        .collect(java.util.stream.Collectors.toSet());
        Set<String> newPerms = newD.permissions().stream()
                .map(PluginDescriptor.Permission::action)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> oldSettings = oldD == null ? Set.of() : Set.copyOf(oldD.settingKeys());
        Set<String> newSettings = Set.copyOf(newD.settingKeys());
        Set<String> oldTopics = oldD == null ? Set.of() : Set.copyOf(oldD.streamTopics());
        Set<String> newTopics = Set.copyOf(newD.streamTopics());
        Set<String> oldTools = oldD == null
                ? Set.of()
                : oldD.mcpTools().stream()
                        .map(PluginDescriptor.McpTool::name)
                        .collect(java.util.stream.Collectors.toSet());
        Set<String> newTools = newD.mcpTools().stream()
                .map(PluginDescriptor.McpTool::name)
                .collect(java.util.stream.Collectors.toSet());
        return new ContributionDiff(
                added(newPerms, oldPerms),
                added(oldPerms, newPerms),
                added(newSettings, oldSettings),
                added(oldSettings, newSettings),
                added(newTopics, oldTopics),
                added(oldTopics, newTopics),
                added(newTools, oldTools),
                added(oldTools, newTools));
    }

    private static Set<String> added(Set<String> a, Set<String> b) {
        Set<String> result = new LinkedHashSet<>(a);
        result.removeAll(b);
        return Set.copyOf(result);
    }

    /** How many distinct roles hold each removed permission — {@code role_permission} is owned by
     * {@code kernel.security}, which {@code kernel.plugin} may not depend on, so this is a plain
     * read-only query against the table's own name rather than a cross-module call. */
    private Map<String, Integer> rolesLosingPermission(Set<String> removedPermissions) {
        if (removedPermissions.isEmpty()) {
            return Map.of();
        }
        String placeholders =
                String.join(",", removedPermissions.stream().map(p -> "?").toList());
        String sql = "SELECT action, count(DISTINCT role_id) FROM role_permission WHERE action IN (" + placeholders
                + ") GROUP BY action";
        Map<String, Integer> result = new java.util.LinkedHashMap<>();
        jdbc.query(
                sql,
                rs -> {
                    result.put(rs.getString(1), rs.getInt(2));
                },
                removedPermissions.toArray());
        for (String permission : removedPermissions) {
            result.putIfAbsent(permission, 0);
        }
        return result;
    }

    private List<String> missingRequires(PluginDescriptor descriptor) {
        List<String> missing = new java.util.ArrayList<>();
        for (String dependency : descriptor.requires()) {
            boolean builtinOk = featureRegistry.isEnabled(dependency);
            boolean pluginOk = installs.findById(dependency)
                    .map(e -> e.status() == PluginInstallStatus.ACTIVE)
                    .orElse(false);
            if (!builtinOk && !pluginOk) {
                missing.add(dependency);
            }
        }
        return missing;
    }

    /**
     * design.md §2: activation is refused, with the reason, once 10 core connections plus 3 per
     * active plugin (counting this one) would exceed 80% of Postgres' own {@code max_connections}.
     */
    private void checkConnectionBudget(String pluginId) {
        long activeCount = installs.countByStatus(PluginInstallStatus.ACTIVE.dbValue());
        boolean alreadyActive = installs.findById(pluginId)
                .map(e -> e.status() == PluginInstallStatus.ACTIVE)
                .orElse(false);
        long activeInclThis = alreadyActive ? activeCount : activeCount + 1;
        int needed = CORE_CONNECTIONS + POOL_PER_PLUGIN * (int) activeInclThis;
        int max = readMaxConnections();
        int threshold = (int) (max * 0.8);
        if (needed > threshold) {
            throw new PluginRefusedException(List.of(new Violation(
                    "connection-budget",
                    "Activating would need %d connections (10 core + 3 x %d active plugins), over 80%% of max_connections=%d (%d)."
                            .formatted(needed, activeInclThis, max, threshold),
                    "Disable another plugin first, or raise Postgres' max_connections.")));
        }
    }

    private int readMaxConnections() {
        String raw = jdbc.queryForObject("SHOW max_connections", String.class);
        return Integer.parseInt(raw.trim());
    }

    private Optional<PluginRuntime> activeRuntime(String id) {
        return registry.get(id).filter(Active.class::isInstance).map(s -> ((Active) s).runtime());
    }

    private void step(PlanContext ctx, String actor, String name) {
        store.update(ctx.descriptor().id(), e -> e.step(name));
        logStep(ctx, actor, name, "in-progress");
    }

    private void logStep(PlanContext ctx, String actor, String step, String outcome) {
        String id = ctx.descriptor().id();
        String from = ctx.previousDescriptor() == null
                ? null
                : ctx.previousDescriptor().version();
        String to = ctx.descriptor().version();
        String sha256 = ctx.sha256();
        log.info(
                "plugin-lifecycle id={} from={} to={} sha256={} actor={} step={} outcome={}",
                id,
                from,
                to,
                sha256,
                actor,
                step,
                outcome);
        listeners.forEach(l -> {
            try {
                l.onStep(id, from, to, sha256, actor, step, outcome);
            } catch (RuntimeException e) {
                log.warn("Plugin lifecycle listener {} threw for plugin '{}'", l, id, e);
            }
        });
    }

    private static String describeFailure(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.toString();
    }

    private static String schemaName(String pluginId) {
        return "plugin_" + pluginId.replace('-', '_');
    }

    private static String tagFor(PluginDescriptor descriptor) {
        return "pre-" + descriptor.version();
    }

    private static String quoteIdent(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
