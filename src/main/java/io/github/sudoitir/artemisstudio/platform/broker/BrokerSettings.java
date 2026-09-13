package io.github.sudoitir.artemisstudio.platform.broker;

import io.github.sudoitir.artemisstudio.kernel.core.ArtemisStudioProperties;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingDef;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingDef.Kind;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsContribution;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** The per-node call ceiling, Jolokia timeouts and the bulk safety cap (ADR-0010, ADR-0022). */
@Component
@RequiredArgsConstructor
public class BrokerSettings implements SettingsContribution {

    public static final String RATE_LIMIT = "rate-limit.calls-per-second";
    public static final String CONNECT_TIMEOUT = "broker.connect-timeout";
    public static final String READ_TIMEOUT = "broker.read-timeout";
    /** Server-enforced ceiling on one destructive message operation (ADR-0022). */
    public static final String BULK_CAP = "safety.bulk-cap";

    private final ArtemisStudioProperties defaults;
    private final NodeCallLimiter limiter;
    private final BrokerClientFactory brokerClients;

    @Override
    public String featureId() {
        return "broker";
    }

    @Override
    public List<SettingDef> settings() {
        return List.of(
                new SettingDef(
                        RATE_LIMIT,
                        "Scrape",
                        "Per-node call ceiling",
                        "Management calls per second, per broker node. Studio must never be the load.",
                        Kind.INT,
                        () -> Integer.toString(defaults.rateLimit().managementCallsPerSecond()),
                        s -> limiter.setPermitsPerSecond(s.intValue(RATE_LIMIT))),
                new SettingDef(
                        CONNECT_TIMEOUT,
                        "Broker transport",
                        "Connect timeout",
                        "TCP connect timeout for every Jolokia call. Applies to clients built after the change.",
                        Kind.DURATION,
                        () -> defaults.broker().connectTimeout().toString(),
                        this::applyTimeouts),
                new SettingDef(
                        READ_TIMEOUT,
                        "Broker transport",
                        "Read timeout",
                        "Response timeout for every Jolokia call. Raise it for a broker with very large queue sets.",
                        Kind.DURATION,
                        () -> defaults.broker().readTimeout().toString(),
                        this::applyTimeouts),
                new SettingDef(
                        BULK_CAP,
                        "Safety",
                        "Bulk operation cap",
                        "Most messages one destructive operation may touch before it needs an explicit override.",
                        Kind.INT,
                        () -> Integer.toString(defaults.safety().bulkCap()),
                        null));
    }

    private void applyTimeouts(io.github.sudoitir.artemisstudio.kernel.settings.SettingsService s) {
        brokerClients.setTimeouts(s.duration(CONNECT_TIMEOUT), s.duration(READ_TIMEOUT));
    }
}
