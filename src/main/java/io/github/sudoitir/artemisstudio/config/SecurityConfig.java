package io.github.sudoitir.artemisstudio.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Placeholder security. The skeleton runs fully open so the workspace is
 * verifiable ({@code /actuator/health}, the static shell, and — later — the
 * dev API) without an auth flow.
 *
 * <p>Phase 8 replaces this with local users in Postgres, a session cookie,
 * three roles, and per-cluster scoping (OIDC follows in v1.0). See
 * {@code docs/roadmap.md}. Do not build features that assume this stays open.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
