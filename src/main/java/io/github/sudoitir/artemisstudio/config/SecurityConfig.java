package io.github.sudoitir.artemisstudio.config;

import io.github.sudoitir.artemisstudio.security.ApiTokenAuthenticationFilter;
import io.github.sudoitir.artemisstudio.security.ApiTokenService;
import io.github.sudoitir.artemisstudio.security.CsrfCookieFilter;
import io.github.sudoitir.artemisstudio.security.MustChangePasswordFilter;
import io.github.sudoitir.artemisstudio.security.PermissionResolver;
import io.github.sudoitir.artemisstudio.security.Permissions;
import io.github.sudoitir.artemisstudio.security.oidc.OidcAuthenticationSuccessHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
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
 * Session-cookie authentication and dynamic, scope-walked authorization
 * (ADR-0037, ADR-0038). Replaces the Phase 1-7 placeholder that ran the whole
 * API {@code permitAll()} — see git history for that version and
 * {@code docs/adr/0023-audit-actor-before-authentication.md} (superseded by
 * ADR-0041) for why it existed.
 *
 * <p>Every {@code /api/**} path requires authentication except
 * {@code /api/v1/auth/login}; the served SPA shell and its static assets stay
 * public so an unauthenticated browser can load the login screen at all.
 *
 * <p>{@code /mcp} is authenticated on the same terms (ADR-0046): the MCP surface
 * reuses the ADR-0039 personal API tokens rather than adding a second credential
 * store, so the existing {@link ApiTokenAuthenticationFilter} is the whole of it.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(
            HttpSecurity http,
            ApiTokenService apiTokenService,
            HandlerExceptionResolver handlerExceptionResolver,
            CsrfTokenRepository csrfTokenRepository,
            ObjectProvider<ClientRegistrationRepository> clientRegistrations,
            OidcAuthenticationSuccessHandler oidcSuccessHandler,
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
                                new AuthorizationDecision(perm.can(Permissions.SETTINGS_WRITE)))
                        .requestMatchers("/api/**", "/mcp", "/mcp/**")
                        .authenticated()
                        // The SPA shell and its static assets (SpaRoutingConfig) must stay
                        // reachable unauthenticated, or the login page itself cannot load.
                        .anyRequest()
                        .permitAll())
                // SecurityContextHolderFilter loads (empty, session-less) context from the
                // repository and would overwrite a bearer authentication set before it runs —
                // this filter must come after, not before.
                .addFilterAfter(new ApiTokenAuthenticationFilter(apiTokenService), SecurityContextHolderFilter.class)
                .addFilterAfter(
                        new MustChangePasswordFilter(handlerExceptionResolver), SecurityContextHolderFilter.class)
                .addFilterAfter(new CsrfCookieFilter(), org.springframework.security.web.csrf.CsrfFilter.class);

        // OIDC/SSO (ADR-0040) is opt-in: with no spring.security.oauth2.client.registration.*
        // configured, Boot creates no ClientRegistrationRepository bean at all, and
        // .oauth2Login() must not be called in that case (it would fail to wire).
        if (clientRegistrations.getIfAvailable() != null) {
            http.oauth2Login(oauth2 -> oauth2.successHandler(oidcSuccessHandler));
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
