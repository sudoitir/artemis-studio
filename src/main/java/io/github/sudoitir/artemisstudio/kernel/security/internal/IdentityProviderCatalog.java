package io.github.sudoitir.artemisstudio.kernel.security.internal;

import io.github.sudoitir.artemisstudio.kernel.plugin.IdentityProviderListing;
import io.github.sudoitir.artemisstudio.kernel.security.BearerIdentityProvider;
import io.github.sudoitir.artemisstudio.kernel.security.CredentialIdentityProvider;
import io.github.sudoitir.artemisstudio.kernel.security.IdentityProvider;
import io.github.sudoitir.artemisstudio.kernel.security.IdentityProviders;
import io.github.sudoitir.artemisstudio.kernel.security.RedirectIdentityProvider;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The providers a caller can sign in with, for the login screen and the manifest. Bearer
 * providers are for automation, not people, so they are not listed.
 */
@Component
@RequiredArgsConstructor
public class IdentityProviderCatalog implements IdentityProviderListing {

    private final List<IdentityProviders> contributions;

    @Override
    public List<Entry> providers() {
        return contributions.stream()
                .flatMap(c -> c.providers().stream())
                .filter(p -> !(p instanceof BearerIdentityProvider))
                .map(IdentityProviderCatalog::entry)
                .toList();
    }

    private static Entry entry(IdentityProvider p) {
        return switch (p) {
            case CredentialIdentityProvider c -> new Entry(c.id(), "CREDENTIAL", c.label(), null);
            case RedirectIdentityProvider r -> new Entry(r.id(), "REDIRECT", r.label(), r.startPath());
            case BearerIdentityProvider b -> new Entry(b.id(), "BEARER", b.label(), null);
        };
    }
}
