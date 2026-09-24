package io.github.sudoitir.artemisstudio.kernel.plugin.internal.host;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginProperties;
import io.github.sudoitir.artemisstudio.kernel.plugin.internal.validation.Violation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.cloud.CloudPlatform;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Restarts Studio when a plugin needs it (ADR-0104): a graceful exit — every plugin closed, the
 * boot record marked clean, the process ended — that the supervisor turns into a restart. Only
 * ever when a supervisor is known to restart it: Kubernetes (detected), or an installation that
 * says so ({@code artemis-studio.plugins.restart.supervised=true}, which the compose files set
 * alongside {@code restart: unless-stopped}). Anywhere else, exiting would leave Studio down, so
 * the operator is shown the command instead.
 *
 * <p>Not the actuator {@code restart} endpoint: that refreshes the context inside the same JVM, so
 * it never frees what a restart is for — a plugin class loader or metaspace that did not unload —
 * and exposing restart or shutdown over HTTP is an attack surface Studio does not need.
 */
@Component
@Slf4j
public class StudioRestart {

    /** Not 0: a supervisor that restarts only on failure ({@code on-failure}) must restart this too. */
    static final int EXIT_CODE = 75;

    /** Lets the response that asked for it reach the browser before the process ends. */
    static final Duration DELAY = Duration.ofSeconds(3);

    /** A manual restart is refused this soon after boot, so nothing can hold Studio in a restart loop. */
    static final Duration MIN_UPTIME = Duration.ofMinutes(2);

    /** What an operator runs when Studio cannot restart itself. */
    public static final String MANUAL_COMMAND = "docker compose restart studio";

    private final ConfigurableApplicationContext context;
    private final PluginProperties properties;
    private final Environment environment;
    private final Clock clock;
    private final IntConsumer exit;
    private final Instant startedAt;
    private final AtomicBoolean restarting = new AtomicBoolean();

    @Autowired
    public StudioRestart(
            ConfigurableApplicationContext context, PluginProperties properties, Environment environment, Clock clock) {
        this(context, properties, environment, clock, System::exit);
    }

    StudioRestart(
            ConfigurableApplicationContext context,
            PluginProperties properties,
            Environment environment,
            Clock clock,
            IntConsumer exit) {
        this.context = context;
        this.properties = properties;
        this.environment = environment;
        this.clock = clock;
        this.exit = exit;
        this.startedAt = clock.instant();
    }

    /** Whether something will start Studio again after it exits. */
    public boolean supervised() {
        Boolean configured = properties.restart().supervised();
        return configured != null ? configured : CloudPlatform.getActive(environment) == CloudPlatform.KUBERNETES;
    }

    public boolean restarting() {
        return restarting.get();
    }

    /** When a manual restart is allowed again, while it is not yet. */
    public Optional<Instant> manualRestartAllowedAt() {
        Instant allowed = startedAt.plus(MIN_UPTIME);
        return clock.instant().isBefore(allowed) ? Optional.of(allowed) : Optional.empty();
    }

    /** A restart an operator asked for directly, subject to the cool-down after boot. */
    public void restartOnRequest(String reason) {
        manualRestartAllowedAt().ifPresent(at -> {
            throw new PluginRefusedException(List.of(new Violation(
                    "restart-cooldown",
                    "Studio started less than %d minutes ago.".formatted(MIN_UPTIME.toMinutes()),
                    "Try again after " + at + ".")));
        });
        restart(reason);
    }

    /**
     * Ends the process after {@link #DELAY}, gracefully; the supervisor starts it again. A second
     * call while one is pending does nothing.
     */
    public void restart(String reason) {
        if (!supervised()) {
            throw new PluginRefusedException(List.of(new Violation(
                    "restart-unsupervised",
                    "Nothing would start Studio again after it stops, so it will not stop itself.",
                    "Run `%s` (or restart it the way you run it), or set artemis-studio.plugins.restart.supervised=true where a supervisor restarts it."
                            .formatted(MANUAL_COMMAND))));
        }
        if (!restarting.compareAndSet(false, true)) {
            return;
        }
        log.warn("studio-restart in={}s reason=\"{}\"", DELAY.toSeconds(), reason);
        // A platform thread that is not a daemon: the JVM must not end before this has run.
        Thread.ofPlatform().name("studio-restart").daemon(false).start(() -> {
            try {
                Thread.sleep(DELAY);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exit.accept(SpringApplication.exit(context, () -> EXIT_CODE));
        });
    }
}
