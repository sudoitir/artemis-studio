package io.github.sudoitir.artemisstudio.kernel.approval;

import io.github.sudoitir.artemisstudio.kernel.gate.ApprovalProviderRegistry;
import io.github.sudoitir.artemisstudio.kernel.inbox.InboxService;
import io.github.sudoitir.artemisstudio.kernel.inbox.Notice;
import io.github.sudoitir.artemisstudio.kernel.inbox.Notice.Severity;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.source.ConfigurationProperty;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.stereotype.Component;

/**
 * Break-glass (ADR-0184): {@code artemis-studio.gate.break-glass=<reason>} lets every gated operation run without
 * asking the provider, for recovering from a broken one. It is accepted only from the process environment or a JVM
 * system property, where only whoever runs the deployment can put it; supplied from anywhere else, the database-backed
 * configuration of ADR-0047 above all, Studio refuses to start. While it is set, every bypass is audited, a WARN line is
 * logged at boot and every hour, approvers are told, and the console shows a banner. Held requests stay held.
 */
@Component
@Slf4j
public class BreakGlass {

    public static final String PROPERTY = "artemis-studio.gate.break-glass";

    private static final Set<String> ALLOWED_SOURCES = Set.of(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);

    private final String reason;
    private final ApprovalProviderRegistry providers;
    private final InboxService inbox;
    private final AtomicBoolean approversTold = new AtomicBoolean();

    BreakGlass(ConfigurableEnvironment environment, ApprovalProviderRegistry providers, InboxService inbox) {
        this.reason = reasonFrom(environment);
        this.providers = providers;
        this.inbox = inbox;
    }

    /**
     * The break-glass reason, or null when it is not set.
     *
     * @throws IllegalStateException when any source other than the environment or system properties supplies it,
     *     relaxed spellings included
     */
    static String reasonFrom(ConfigurableEnvironment environment) {
        ConfigurationPropertyName name = ConfigurationPropertyName.of(PROPERTY);
        String value = null;
        for (ConfigurationPropertySource source : ConfigurationPropertySources.get(environment)) {
            ConfigurationProperty property = source.getConfigurationProperty(name);
            if (property == null) {
                continue;
            }
            String sourceName = source.getUnderlyingSource() instanceof PropertySource<?> underlying
                    ? underlying.getName()
                    : String.valueOf(source.getUnderlyingSource());
            if (!ALLOWED_SOURCES.contains(sourceName)) {
                throw new IllegalStateException(PROPERTY + " is set by the configuration source '" + sourceName
                        + "'. Break-glass is accepted only from the process environment (ARTEMIS_STUDIO_GATE_BREAK_GLASS)"
                        + " or a JVM system property, never from the database or a configuration file. Remove it"
                        + " there and restart.");
            }
            if (value == null && property.getValue() != null) {
                value = property.getValue().toString().strip();
            }
        }
        return value == null || value.isEmpty() ? null : value;
    }

    public boolean active() {
        return reason != null;
    }

    /** Why it is set, as the deployment says; null when it is not. */
    public String reason() {
        return reason;
    }

    @EventListener
    void onReady(ApplicationReadyEvent ready) {
        remind();
    }

    /** The boot and hourly WARN, and once per run the approvers' inbox item. */
    void remind() {
        if (!active()) {
            return;
        }
        log.warn(
                "approval-gate break-glass=on reason=\"{}\": every gated operation runs without approval until {} is"
                        + " removed from the environment",
                reason,
                PROPERTY);
        if (approversTold.get()) {
            return;
        }
        Optional<String> armed = providers.armedProviderId();
        Optional<ApprovalProviderRegistry.AttachedProvider> provider = armed.flatMap(providers::attached);
        if (provider.isEmpty()) {
            return;
        }
        inbox.postToHolders(
                ApprovalNotices.SOURCE,
                new Notice(
                        "approval.break-glass",
                        Severity.DANGER,
                        "Break-glass is on: operations run without approval",
                        "The deployment set break-glass (" + truncate(reason) + "). Every gated operation runs without"
                                + " asking for approval, and each one is audited as bypassed.",
                        null,
                        "break-glass",
                        Map.of("reason", truncate(reason)),
                        null),
                provider.get().approverPermission(),
                null,
                List.of());
        approversTold.set(true);
    }

    private static String truncate(String text) {
        return text.length() <= 200 ? text : text.substring(0, 199) + "…";
    }
}
