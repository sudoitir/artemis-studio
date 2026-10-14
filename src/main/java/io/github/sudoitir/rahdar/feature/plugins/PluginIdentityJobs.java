package io.github.sudoitir.rahdar.feature.plugins;

import io.github.sudoitir.rahdar.kernel.jobs.ScheduledJob;
import io.github.sudoitir.rahdar.kernel.security.PluginIdentityRevalidation;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Takes access away from users a plugin's sign-in provider no longer vouches for (ADR-0156). */
@Configuration(proxyBeanMethods = false)
class PluginIdentityJobs {

    @Bean
    ScheduledJob pluginIdentityRevalidationJob(PluginIdentityRevalidation revalidation) {
        return ScheduledJob.fixedDelay(
                "plugin-identity-revalidation",
                "plugins",
                ScheduledJob.Scope.INSTALLATION,
                () -> Duration.ofMinutes(5),
                revalidation::run);
    }
}
