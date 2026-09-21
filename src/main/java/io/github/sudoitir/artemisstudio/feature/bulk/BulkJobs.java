package io.github.sudoitir.artemisstudio.feature.bulk;

import io.github.sudoitir.artemisstudio.kernel.jobs.ScheduledJob;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class BulkJobs {

    /** Daily, off-peak: expired previews are small, and nothing reads them. */
    @Bean
    ScheduledJob bulkPreviewHousekeepingJob(BulkService bulk) {
        return ScheduledJob.cron(
                "bulk-preview-housekeeping", "bulk", () -> "0 20 4 * * *", bulk::deleteExpiredPreviews);
    }
}
