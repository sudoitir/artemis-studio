package io.github.sudoitir.artemisstudio.kernel.settings;

import io.github.sudoitir.artemisstudio.kernel.jobs.ScheduledJob;
import io.github.sudoitir.artemisstudio.kernel.security.SecretRotations;
import io.github.sudoitir.artemisstudio.kernel.security.SecretVault;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The rotation sweep, and the refresh that keeps every replica wrapping with the current key version (ADR-0132 D5). */
@Configuration(proxyBeanMethods = false)
class SecretRotationJobs {

    @Bean
    ScheduledJob secretRotationSweepJob(SecretRotations rotations) {
        return ScheduledJob.fixedDelay(
                "secret-rotation-sweep",
                "settings",
                ScheduledJob.Scope.INSTALLATION,
                () -> Duration.ofSeconds(5),
                rotations::sweep);
    }

    @Bean
    ScheduledJob secretKeyVersionRefreshJob(SecretVault vault) {
        return ScheduledJob.fixedDelay(
                "secret-key-version-refresh",
                "settings",
                ScheduledJob.Scope.INSTANCE,
                () -> Duration.ofSeconds(10),
                vault::refreshCurrentVersion);
    }
}
