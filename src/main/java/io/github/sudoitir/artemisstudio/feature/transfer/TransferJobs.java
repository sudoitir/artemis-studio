package io.github.sudoitir.artemisstudio.feature.transfer;

import io.github.sudoitir.artemisstudio.kernel.jobs.ScheduledJob;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class TransferJobs {

    /** Daily, off-peak: expired previews are small, and nothing reads them. */
    @Bean
    ScheduledJob transferPreviewHousekeepingJob(TransferService transfers) {
        return ScheduledJob.cron(
                "transfer-preview-housekeeping", "transfer", () -> "0 25 4 * * *", transfers::deleteExpiredPreviews);
    }
}
