package io.github.sudoitir.artemisstudio.kernel.security;

import java.util.List;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;

/**
 * One identity module's contribution (ADR-0073): the providers it offers, and — for redirect
 * sign-in only — what the one security chain needs to complete it. A new provider is a module
 * with one of these; the kernel's login path, bearer filter and provider list pick it up.
 */
public interface IdentityProviders {

    /** The providers this module offers now. May depend on configuration, so it is asked each time. */
    List<? extends IdentityProvider> providers();

    /** Add what redirect sign-in needs to the chain. Nothing by default. */
    default void configure(HttpSecurity http) throws Exception {}
}
