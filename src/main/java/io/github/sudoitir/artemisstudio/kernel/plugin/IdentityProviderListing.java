package io.github.sudoitir.artemisstudio.kernel.plugin;

import java.util.List;

/** The sign-in providers an installation offers, for its manifest and login screen (ADR-0073). */
public interface IdentityProviderListing {

    /** {@link Entry#kind()} of a username and password form. */
    String CREDENTIAL = "CREDENTIAL";

    /** {@link Entry#kind()} of a sign-in action elsewhere. */
    String REDIRECT = "REDIRECT";

    List<Entry> providers();

    /**
     * @param kind {@code CREDENTIAL} for a username and password form, {@code REDIRECT} for a
     *     sign-in action elsewhere
     * @param startPath where a redirect provider's sign-in begins; {@code null} otherwise
     */
    record Entry(String id, String kind, String label, String startPath) {}
}
