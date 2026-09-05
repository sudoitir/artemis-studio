package io.github.sudoitir.artemisstudio.service;

import io.github.sudoitir.artemisstudio.persist.AppUserEntity;
import io.github.sudoitir.artemisstudio.persist.AppUserRepository;
import io.github.sudoitir.artemisstudio.persist.AuditService;
import io.github.sudoitir.artemisstudio.security.Actor;
import io.github.sudoitir.artemisstudio.security.ActorResolver;
import io.github.sudoitir.artemisstudio.security.GrantLoader;
import io.github.sudoitir.artemisstudio.security.LoginAttemptLimiter;
import io.github.sudoitir.artemisstudio.security.StudioPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authenticates a JSON login request directly against {@code app_user} (design.md
 * decision 1 — no {@code UsernamePasswordAuthenticationFilter}, since the login
 * body is JSON, not form-encoded). Builds a {@link StudioPrincipal} with no
 * password material on it and persists it to the session explicitly via
 * {@link SecurityContextRepository}, which the framework's load-only
 * {@code SecurityContextHolderFilter} does not do on its own for a
 * programmatically-authenticated request.
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final GrantLoader grantLoader;
    private final LoginAttemptLimiter loginLimiter;
    private final SecurityContextRepository securityContextRepository;
    private final CsrfTokenRepository csrfTokenRepository;
    private final AuditService auditService;
    private final ActorResolver actorResolver;

    @Transactional
    public StudioPrincipal login(
            String username, String password, HttpServletRequest request, HttpServletResponse response) {
        String sourceIp = request.getRemoteAddr();
        if (loginLimiter.isLocked(username, sourceIp)) {
            throw new LoginThrottledException();
        }
        var event = auditService.begin(anonymousActor(request), "LOGIN", "user", username, null, null, null, false);
        AppUserEntity user = users.findByUsername(username).orElse(null);
        if (user == null
                || user.isDisabled()
                || user.getPasswordHash() == null
                || !passwordEncoder.matches(password, user.getPasswordHash())) {
            loginLimiter.recordFailure(username, sourceIp);
            auditService.fail(event, "invalid credentials");
            if (user != null && user.isDisabled()) {
                throw new DisabledException("Account disabled");
            }
            throw new BadCredentialsException("Invalid username or password");
        }
        loginLimiter.recordSuccess(username, sourceIp);
        StudioPrincipal principal = new StudioPrincipal(
                user.getId(), user.getUsername(), grantLoader.loadFor(user.getId()), user.isMustChangePassword());
        authenticate(principal, request, response);
        auditService.succeed(event, 1);
        return principal;
    }

    @Transactional
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        Actor actor = actorResolver.resolve();
        auditService.succeed(auditService.begin(actor, "LOGOUT", "user", actor.username(), null, null, null, false), 1);
        SecurityContextHolder.getContext().setAuthentication(null);
        securityContextRepository.saveContext(SecurityContextHolder.createEmptyContext(), request, response);
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        reissueCsrfToken(request, response);
    }

    @Transactional
    public void changePassword(
            StudioPrincipal principal,
            String currentPassword,
            String newPassword,
            HttpServletRequest request,
            HttpServletResponse response) {
        AppUserEntity user =
                users.findById(principal.userId()).orElseThrow(() -> new NotFoundException("user", principal.userId()));
        if (user.getPasswordHash() == null || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new BadCredentialsException("Current password is incorrect");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        users.save(user);
        auditService.succeed(
                auditService.begin(
                        actorResolver.resolve(),
                        "PASSWORD_CHANGE",
                        "user",
                        user.getUsername(),
                        null,
                        null,
                        null,
                        false),
                1);
        // The session's principal still carries the old mustChangePassword=true —
        // re-authenticate with a fresh one so MustChangePasswordFilter unlocks
        // immediately, without forcing a separate login.
        StudioPrincipal refreshed =
                new StudioPrincipal(user.getId(), user.getUsername(), grantLoader.loadFor(user.getId()), false);
        authenticate(refreshed, request, response);
    }

    private void authenticate(StudioPrincipal principal, HttpServletRequest request, HttpServletResponse response) {
        var authentication =
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        reissueCsrfToken(request, response);
    }

    /**
     * {@code CsrfAuthenticationStrategy} and {@code CsrfLogoutHandler} clear the
     * previous CSRF cookie on login and logout respectively — a documented SPA
     * gotcha (design.md decision 1) — so both paths generate and save a fresh one
     * explicitly rather than waiting for it to be lazily regenerated.
     */
    private void reissueCsrfToken(HttpServletRequest request, HttpServletResponse response) {
        CsrfToken token = csrfTokenRepository.generateToken(request);
        csrfTokenRepository.saveToken(token, request, response);
    }

    private Actor anonymousActor(HttpServletRequest request) {
        return new Actor(Actor.ANONYMOUS, request.getRemoteAddr(), request.getHeader("X-Request-Id"), null);
    }
}
