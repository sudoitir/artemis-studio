package io.github.sudoitir.artemisstudio.kernel.settings;

import io.github.sudoitir.artemisstudio.kernel.security.SessionLifetimes;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingDef.Kind;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The idle and absolute session limits (ADR-0144). Declared by the security module's descriptor but
 * defined here, because security cannot depend on settings. The values are pushed into volatile
 * fields, since every authenticated request reads them.
 */
@Component
public class SessionSettings implements SettingsContribution, SessionLifetimes {

    private static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofMinutes(30);
    private static final Duration DEFAULT_ABSOLUTE_LIFETIME = Duration.ofHours(12);
    private static final String GROUP = "Sessions";

    private volatile Duration idleTimeout = DEFAULT_IDLE_TIMEOUT;
    private volatile Duration absoluteLifetime = DEFAULT_ABSOLUTE_LIFETIME;

    @Override
    public String featureId() {
        return "security";
    }

    @Override
    public List<SettingDef> settings() {
        return List.of(
                new SettingDef(
                        IDLE_TIMEOUT,
                        GROUP,
                        "Idle timeout",
                        "A session ends after this long without the user doing anything in the console."
                                + " Background refreshes do not count.",
                        Kind.DURATION,
                        DEFAULT_IDLE_TIMEOUT::toString,
                        s -> idleTimeout = s.duration(IDLE_TIMEOUT)),
                new SettingDef(
                        ABSOLUTE_LIFETIME,
                        GROUP,
                        "Absolute session lifetime",
                        "A session ends this long after signing in, however active it is.",
                        Kind.DURATION,
                        DEFAULT_ABSOLUTE_LIFETIME::toString,
                        s -> absoluteLifetime = s.duration(ABSOLUTE_LIFETIME)));
    }

    @Override
    public Duration idleTimeout() {
        return idleTimeout;
    }

    @Override
    public Duration absoluteLifetime() {
        return absoluteLifetime;
    }
}
