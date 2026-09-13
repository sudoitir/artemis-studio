package io.github.sudoitir.artemisstudio.app;

import io.github.sudoitir.artemisstudio.feature.alerting.AlertingModule;
import io.github.sudoitir.artemisstudio.feature.apitokens.ApiTokensModule;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.BrokerConfigModule;
import io.github.sudoitir.artemisstudio.feature.events.EventsModule;
import io.github.sudoitir.artemisstudio.feature.identitylocal.IdentityLocalModule;
import io.github.sudoitir.artemisstudio.feature.identityoidc.IdentityOidcModule;
import io.github.sudoitir.artemisstudio.feature.messages.MessagesModule;
import io.github.sudoitir.artemisstudio.feature.metrics.MetricsModule;
import io.github.sudoitir.artemisstudio.feature.queues.QueuesModule;
import io.github.sudoitir.artemisstudio.feature.resources.ResourcesModule;
import io.github.sudoitir.artemisstudio.feature.routing.RoutingModule;
import io.github.sudoitir.artemisstudio.feature.rr.RrModule;
import io.github.sudoitir.artemisstudio.feature.sql.SqlModule;
import io.github.sudoitir.artemisstudio.kernel.audit.AuditModule;
import io.github.sudoitir.artemisstudio.kernel.plugin.FeatureDescriptor;
import io.github.sudoitir.artemisstudio.kernel.plugin.InstalledFeatures;
import io.github.sudoitir.artemisstudio.kernel.security.SecurityModule;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsModule;
import io.github.sudoitir.artemisstudio.kernel.stream.StreamModule;
import io.github.sudoitir.artemisstudio.platform.broker.BrokerModule;
import io.github.sudoitir.artemisstudio.platform.clusters.ClustersModule;
import io.github.sudoitir.artemisstudio.platform.mcp.McpModule;
import io.github.sudoitir.artemisstudio.platform.scrape.ScrapeModule;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The composition root (ADR-0069): the one list of modules built into Studio.
 * Adding a module is one descriptor here and, for an optional feature, one
 * {@code @Import} of its {@code <Id>Feature} configuration.
 */
@Configuration(proxyBeanMethods = false)
public class StudioFeatures {

    /** Every installed module's descriptor, enabled or not. */
    public static List<FeatureDescriptor> descriptors() {
        return List.of(
                SecurityModule.DESCRIPTOR,
                AuditModule.DESCRIPTOR,
                SettingsModule.DESCRIPTOR,
                StreamModule.DESCRIPTOR,
                BrokerModule.DESCRIPTOR,
                ClustersModule.DESCRIPTOR,
                ScrapeModule.DESCRIPTOR,
                McpModule.DESCRIPTOR,
                QueuesModule.DESCRIPTOR,
                ResourcesModule.DESCRIPTOR,
                MessagesModule.DESCRIPTOR,
                RoutingModule.DESCRIPTOR,
                MetricsModule.DESCRIPTOR,
                AlertingModule.DESCRIPTOR,
                EventsModule.DESCRIPTOR,
                RrModule.DESCRIPTOR,
                SqlModule.DESCRIPTOR,
                BrokerConfigModule.DESCRIPTOR,
                ApiTokensModule.DESCRIPTOR,
                IdentityLocalModule.DESCRIPTOR,
                IdentityOidcModule.DESCRIPTOR);
    }

    @Bean
    InstalledFeatures installedFeatures() {
        return new InstalledFeatures(descriptors());
    }
}
