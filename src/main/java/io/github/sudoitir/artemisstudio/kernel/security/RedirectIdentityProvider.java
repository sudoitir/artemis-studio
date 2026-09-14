package io.github.sudoitir.artemisstudio.kernel.security;

/** Sign-in in the browser elsewhere, returning to Studio (ADR-0040, ADR-0073). */
public non-sealed interface RedirectIdentityProvider extends IdentityProvider {

    /** Where the browser goes to begin signing in with this provider. */
    String startPath();
}
