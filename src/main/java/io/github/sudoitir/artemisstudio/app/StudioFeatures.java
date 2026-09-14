package io.github.sudoitir.artemisstudio.app;

import io.github.sudoitir.artemisstudio.feature.alerting.AlertingFeature;
import io.github.sudoitir.artemisstudio.feature.alerting.AlertingModule;
import io.github.sudoitir.artemisstudio.feature.apitokens.ApiTokensFeature;
import io.github.sudoitir.artemisstudio.feature.apitokens.ApiTokensModule;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.BrokerConfigFeature;
import io.github.sudoitir.artemisstudio.feature.brokerconfig.BrokerConfigModule;
import io.github.sudoitir.artemisstudio.feature.events.EventsFeature;
import io.github.sudoitir.artemisstudio.feature.events.EventsModule;
import io.github.sudoitir.artemisstudio.feature.identitylocal.IdentityLocalFeature;
import io.github.sudoitir.artemisstudio.feature.identitylocal.IdentityLocalModule;
import io.github.sudoitir.artemisstudio.feature.identityoidc.IdentityOidcFeature;
import io.github.sudoitir.artemisstudio.feature.identityoidc.IdentityOidcModule;
import io.github.sudoitir.artemisstudio.feature.messages.MessagesFeature;
import io.github.sudoitir.artemisstudio.feature.messages.MessagesModule;
import io.github.sudoitir.artemisstudio.feature.metrics.MetricsFeature;
import io.github.sudoitir.artemisstudio.feature.metrics.MetricsModule;
import io.github.sudoitir.artemisstudio.feature.queues.QueuesFeature;
import io.github.sudoitir.artemisstudio.feature.queues.QueuesModule;
import io.github.sudoitir.artemisstudio.feature.resources.ResourcesFeature;
import io.github.sudoitir.artemisstudio.feature.resources.ResourcesModule;
import io.github.sudoitir.artemisstudio.feature.routing.RoutingFeature;
import io.github.sudoitir.artemisstudio.feature.routing.RoutingModule;
import io.github.sudoitir.artemisstudio.feature.rr.RrFeature;
import io.github.sudoitir.artemisstudio.feature.rr.RrModule;
import io.github.sudoitir.artemisstudio.feature.sql.SqlFeature;
import io.github.sudoitir.artemisstudio.feature.sql.SqlModule;
import io.github.sudoitir.artemisstudio.feature.triage.TriageFeature;
import io.github.sudoitir.artemisstudio.feature.triage.TriageModule;
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
import org.springframework.context.annotation.Import;

/**
 * The composition root (ADR-0069): the one list of modules built into Studio.
 * Adding a module is one descriptor here and, for an optional feature, one
 * {@code @Import} of its {@code <Id>Feature} configuration.
 */
@Configuration(proxyBeanMethods = false)
@Import({
    QueuesFeature.class,
    ResourcesFeature.class,
    MessagesFeature.class,
    RoutingFeature.class,
    MetricsFeature.class,
    AlertingFeature.class,
    EventsFeature.class,
    RrFeature.class,
    SqlFeature.class,
    BrokerConfigFeature.class,
    TriageFeature.class,
    ApiTokensFeature.class,
    IdentityLocalFeature.class,
    IdentityOidcFeature.class
})
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
                TriageModule.DESCRIPTOR,
                ApiTokensModule.DESCRIPTOR,
                IdentityLocalModule.DESCRIPTOR,
                IdentityOidcModule.DESCRIPTOR);
    }

    @Bean
    InstalledFeatures installedFeatures() {
        return new InstalledFeatures(descriptors());
    }
}
