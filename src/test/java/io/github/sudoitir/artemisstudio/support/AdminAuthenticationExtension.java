package io.github.sudoitir.artemisstudio.support;

import io.github.sudoitir.artemisstudio.security.Grant;
import io.github.sudoitir.artemisstudio.security.Permissions;
import io.github.sudoitir.artemisstudio.security.StudioPrincipal;
import java.util.Set;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Installs a full-access {@link StudioPrincipal} into {@link SecurityContextHolder}
 * around each test, so a pre-existing test that calls into
 * {@code @PreAuthorize}-guarded services (Phase 8) does not need to know
 * anything about authentication to keep testing what it was already testing.
 * Register with {@code @ExtendWith(AdminAuthenticationExtension.class)}.
 *
 * <p>These tests build their {@code MockMvc} without
 * {@code .apply(springSecurity())}, so the servlet filter chain never runs;
 * only method security (the {@code @PreAuthorize} AOP interceptor around each
 * {@code @Service} bean) is in play, and that is what this extension satisfies.
 */
public class AdminAuthenticationExtension implements BeforeEachCallback, AfterEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) {
        // userId is null, not a random UUID: audit_event.user_id is a real FK to
        // app_user, and no such row exists in these tests' fixtures.
        StudioPrincipal admin = new StudioPrincipal(
                null,
                "test-admin",
                Set.of(new Grant(Grant.ScopeType.GLOBAL, null, Set.of(Permissions.WILDCARD))),
                false);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(admin, null, admin.getAuthorities()));
    }

    @Override
    public void afterEach(ExtensionContext context) {
        SecurityContextHolder.clearContext();
    }
}
