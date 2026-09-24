package io.github.sudoitir.artemisstudio.kernel.plugin;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code artemis-studio.plugins.*}. {@code studioVersionOverride} exists only so tests can pin
 * the running version without a real {@code build-info.properties} (task 5.2). {@code safeMode}
 * forces the crash-loop guard's safe mode regardless of boot history (design.md §2).
 * {@code startTimeoutSeconds} bounds how long a plugin gets to start at boot before it is marked
 * {@code needs_restart}. {@code initialInstallers} names who may install plugins while nobody can
 * yet — a username, or {@code <registrationId>:<subject>} for a single-sign-on user (ADR-0103).
 * {@code upload.enabled=false} is the kill switch: nothing new can be installed or updated.
 * {@code restart.supervised} says whether something restarts Studio after it exits; unset, it is
 * true only on Kubernetes (ADR-0104).
 */
@ConfigurationProperties(prefix = "artemis-studio.plugins")
public record PluginProperties(
        String studioVersionOverride,
        boolean safeMode,
        Integer startTimeoutSeconds,
        List<String> initialInstallers,
        Upload upload,
        Restart restart) {

    public PluginProperties {
        studioVersionOverride = studioVersionOverride == null ? "" : studioVersionOverride;
        startTimeoutSeconds = startTimeoutSeconds == null ? 60 : startTimeoutSeconds;
        initialInstallers = initialInstallers == null ? List.of() : List.copyOf(initialInstallers);
        upload = upload == null ? new Upload(true) : upload;
        restart = restart == null ? new Restart(null) : restart;
    }

    public record Upload(boolean enabled) {}

    public record Restart(Boolean supervised) {}
}
