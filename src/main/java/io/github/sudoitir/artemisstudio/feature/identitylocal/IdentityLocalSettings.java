package io.github.sudoitir.artemisstudio.feature.identitylocal;

import io.github.sudoitir.artemisstudio.kernel.settings.SettingDef;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingDef.Kind;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsContribution;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** What a local password must satisfy (ADR-0144), and how long a trusted device stays trusted (ADR-0143). */
@Component
@RequiredArgsConstructor
public class IdentityLocalSettings implements SettingsContribution {

    public static final String PASSWORD_MIN_LENGTH = "identity-local.password.min-length";
    public static final String BREACH_LOOKUP = "identity-local.password.breach-lookup";
    public static final String TRUSTED_DEVICE_LIFETIME = "identity-local.mfa.trusted-device-lifetime";

    private static final String GROUP = "Password login";

    private final IdentityLocalProperties defaults;

    @Override
    public String featureId() {
        return "identity-local";
    }

    @Override
    public List<SettingDef> settings() {
        return List.of(
                new SettingDef(
                        PASSWORD_MIN_LENGTH,
                        GROUP,
                        "Password minimum length",
                        "The fewest characters a new local password may have. Longer is better than complicated.",
                        Kind.INT,
                        () -> Integer.toString(defaults.passwordMinLength()),
                        null),
                new SettingDef(
                        BREACH_LOOKUP,
                        GROUP,
                        "Check new passwords against known breaches",
                        "When on, a new password is also looked up at api.pwnedpasswords.com. Only the first five"
                                + " characters of its SHA-1 leave Studio, and an unreachable service lets the password"
                                + " through. The offline list of the most common passwords is always checked.",
                        Kind.BOOLEAN,
                        () -> "false",
                        null),
                new SettingDef(
                        TRUSTED_DEVICE_LIFETIME,
                        GROUP,
                        "Trusted device lifetime",
                        "After giving a second factor at sign-in, a user may trust that browser for this long, so a"
                                + " password alone signs in from it. 0 turns trusted devices off. Users can revoke"
                                + " their devices at any time, and a password change, a factor reset and disabling the"
                                + " account revoke them.",
                        Kind.DURATION_OR_OFF,
                        () -> defaults.trustedDeviceLifetime().toString(),
                        null));
    }
}
