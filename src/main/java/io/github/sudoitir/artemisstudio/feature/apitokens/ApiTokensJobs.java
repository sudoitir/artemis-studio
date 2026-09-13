package io.github.sudoitir.artemisstudio.feature.apitokens;

import io.github.sudoitir.artemisstudio.kernel.jobs.ScheduledJob;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Writes each token's last use at most once a minute (api-tokens spec). */
@Configuration(proxyBeanMethods = false)
class ApiTokensJobs {

    @Bean
    ScheduledJob apiTokenLastUsedFlushJob(ApiTokenService tokens) {
        return ScheduledJob.fixedDelay(
                "api-token-last-used-flush", "apitokens", () -> Duration.ofMinutes(1), tokens::flushLastUsed);
    }
}
