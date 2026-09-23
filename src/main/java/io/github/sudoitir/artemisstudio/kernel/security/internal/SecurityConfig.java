package io.github.sudoitir.artemisstudio.kernel.security.internal;

import io.github.sudoitir.artemisstudio.kernel.security.IdentityProviders;
import io.github.sudoitir.artemisstudio.kernel.security.PermissionResolver;
import io.github.sudoitir.artemisstudio.kernel.security.SettingsPermissions;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * The one security chain: session-cookie authentication and dynamic, scope-walked
 * authorization (ADR-0037, ADR-0038), assembled from the installed identity providers
 * (ADR-0073).
 *
 * <p>Every {@code /api/**} path requires authentication except sign-in and the provider list;
 * the served SPA shell and its static assets stay public so an unauthenticated browser can load
 * the login screen at all.
 *
 * <p>{@code /mcp} is authenticated on the same terms (ADR-0046): bearer providers — personal
 * API tokens (ADR-0039) — authenticate it, with no second credential store.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(
            HttpSecurity http,
            List<IdentityProviders> identityProviders,
            HandlerExceptionResolver handlerExceptionResolver,
            CsrfTokenRepository csrfTokenRepository,
            PermissionResolver perm)
            throws Exception {
        http.securityContext(sc -> sc.securityContextRepository(securityContextRepository()))
                .sessionManagement(session -> session.sessionCreationPolicy(
                        org.springframework.security.config.http.SessionCreationPolicy.IF_REQUIRED))
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        // Bearer-token requests carry no ambient browser credential, so there is
                        // nothing for a cross-site request to ride on (design.md decision 1).
                        .ignoringRequestMatchers(request -> request.getHeader("Authorization") != null))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(
                        new HttpStatusEntryPoint(org.springframework.http.HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(auth -> auth.requestMatchers(
                                "/api/v1/auth/login",
                                "/api/v1/auth/providers",
                                "/actuator/health",
                                "/actuator/health/**")
                        .permitAll()
                        // POST /actuator/refresh re-reads the Environment, so it is a
                        // configuration change and is gated like one. Without this rule it
                        // would fall through to the permitAll() below with the rest of
                        // /actuator/** and be callable by anyone who can reach the port.
                        .requestMatchers("/actuator/refresh")
                        // The resolver is called directly rather than through a SpEL
                        // "@perm.can(...)" expression: WebExpressionAuthorizationManager
                        // evaluates without a bean resolver, so the @perm reference fails
                        // at request time rather than at startup.
                        .access((authentication, context) ->
                                new AuthorizationDecision(perm.can(SettingsPermissions.SETTINGS_WRITE)))
                        .requestMatchers("/api/**", "/mcp", "/mcp/**")
                        .authenticated()
                        // A plugin's UI runs with the full rights of whoever is viewing it
                        // (design.md §7), so its own assets are gated exactly like /api/** rather
                        // than falling into the SPA shell's public catch-all below.
                        .requestMatchers("/plugin-ui/**")
                        .authenticated()
                        // The SPA shell and its static assets (SpaRoutingConfig) must stay
                        // reachable unauthenticated, or the login page itself cannot load.
                        .anyRequest()
                        .permitAll())
                // SecurityContextHolderFilter loads (empty, session-less) context from the
                // repository and would overwrite a bearer authentication set before it runs —
                // this filter must come after, not before.
                .addFilterAfter(new BearerAuthenticationFilter(identityProviders), SecurityContextHolderFilter.class)
                .addFilterAfter(
                        new MustChangePasswordFilter(handlerExceptionResolver), SecurityContextHolderFilter.class)
                .addFilterAfter(new CsrfCookieFilter(), org.springframework.security.web.csrf.CsrfFilter.class);

        // Redirect sign-in (ADR-0040) adds what it needs; a module with nothing to add adds nothing.
        for (IdentityProviders contribution : identityProviders) {
            contribution.configure(http);
        }
        return http.build();
    }

    /** Combines session storage (form login) with the per-request attribute cache Spring Security expects. */
    private static SecurityContextRepository securityContextRepository() {
        return new DelegatingSecurityContextRepository(
                new HttpSessionSecurityContextRepository(), new RequestAttributeSecurityContextRepository());
    }

    @Bean
    SecurityContextRepository securityContextRepositoryBean() {
        return securityContextRepository();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    CsrfTokenRepository csrfTokenRepository() {
        return CookieCsrfTokenRepository.withHttpOnlyFalse();
    }
}
