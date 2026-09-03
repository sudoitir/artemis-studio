package io.github.sudoitir.artemisstudio.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Placeholder security. The app runs fully open so Phase 1's API is usable
 * without an auth flow.
 *
 * <p>Phase 8 replaces this with local users in Postgres, a session cookie,
 * three roles, and per-cluster scoping (OIDC follows in v1.0). See
 * {@code docs/roadmap.md}. Do not build features that assume this stays open.
 *
 * <p>Phase 1 introduces stored broker credentials and mutating endpoints, so an
 * unauthenticated instance on a non-loopback address is a real exposure. Until
 * Phase 8 lands, {@link #warnIfExposed} logs a startup WARN in that case, and
 * the dev compose file binds to {@code 127.0.0.1} (ADR-0002 area).
 */
@Configuration
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    @EventListener(ApplicationReadyEvent.class)
    void warnIfExposed(ApplicationReadyEvent event) {
        Environment env = event.getApplicationContext().getEnvironment();
        String address = env.getProperty("server.address");
        if (!isLoopback(address)) {
            log.warn(
                    "Artemis Studio API is UNAUTHENTICATED (auth arrives in Phase 8) and bound to {} "
                            + "— it exposes broker credentials and mutating endpoints to anyone who can reach "
                            + "this port. Bind to 127.0.0.1 or put an authenticating proxy in front until then.",
                    address == null ? "all interfaces" : address);
        }
    }

    private static boolean isLoopback(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        String a = address.trim();
        return a.equals("127.0.0.1") || a.equals("::1") || a.equalsIgnoreCase("localhost") || a.startsWith("127.");
    }
}
