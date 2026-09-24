package io.github.sudoitir.artemisstudio.feature.plugins.messaging.internal;

import io.github.sudoitir.artemisstudio.feature.plugins.messaging.PluginMessagingProperties;
import io.github.sudoitir.artemisstudio.kernel.jobs.ScheduledJob;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class PluginMessagingJobs {

    /** Converges plugins' message registrations; the interval bounds how soon a change is acted on. */
    @Bean
    ScheduledJob pluginMessagingReconcileJob(
            PluginMessagingReconciler reconciler, PluginMessagingProperties properties) {
        return ScheduledJob.fixedDelay(
                "plugin-messaging-reconcile", "plugins", properties::reconcileInterval, reconciler::reconcile);
    }
}
