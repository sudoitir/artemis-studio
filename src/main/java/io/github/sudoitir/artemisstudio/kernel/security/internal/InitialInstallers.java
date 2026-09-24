package io.github.sudoitir.artemisstudio.kernel.security.internal;

import io.github.sudoitir.artemisstudio.kernel.plugin.PluginInstallers;
import io.github.sudoitir.artemisstudio.kernel.plugin.PluginProperties;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@code artemis-studio.plugins.initial-installers} (ADR-0103): while nobody can install plugins,
 * a user the configuration names becomes an installer when they sign in. Once anyone is an
 * installer, the list is ignored — installers then decide who else is one, and a removal is never
 * undone by the next sign-in.
 */
@Component
@RequiredArgsConstructor
public class InitialInstallers {

    private final PluginProperties properties;
    private final PluginInstallers installers;
    private final AppUserRepository users;

    public void grantIfNoneYet(UUID userId) {
        if (properties.initialInstallers().isEmpty() || !installers.none()) {
            return;
        }
        AppUserEntity user = users.findById(userId).orElse(null);
        if (user != null && properties.initialInstallers().stream().anyMatch(entry -> names(entry, user))) {
            installers.grant(userId, "configuration");
        }
    }

    /** {@code <registrationId>:<subject>} names a single-sign-on user exactly; anything else is a username. */
    private static boolean names(String entry, AppUserEntity user) {
        String value = entry.trim();
        int colon = value.indexOf(':');
        if (colon > 0 && user.getExternalSubject() != null) {
            return value.substring(0, colon).equals(user.getProviderId())
                    && value.substring(colon + 1).equals(user.getExternalSubject());
        }
        return value.equals(user.getUsername());
    }
}
