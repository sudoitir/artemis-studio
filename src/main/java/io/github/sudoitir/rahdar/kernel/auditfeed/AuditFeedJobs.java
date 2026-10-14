package io.github.sudoitir.rahdar.kernel.auditfeed;

import io.github.sudoitir.rahdar.kernel.jobs.ScheduledJob;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class AuditFeedJobs {

    @Bean
    ScheduledJob auditDeliverySchedule(AuditDeliveryJob delivery) {
        return ScheduledJob.fixedDelay(
                "audit-delivery",
                AuditFeedModule.ID,
                ScheduledJob.Scope.INSTALLATION,
                () -> Duration.ofSeconds(2),
                delivery::tick);
    }
}
