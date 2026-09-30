package io.github.sudoitir.artemisstudio.kernel.security;

import io.github.sudoitir.artemisstudio.kernel.security.internal.InitialInstallers;
import io.github.sudoitir.artemisstudio.kernel.security.internal.SessionTerminator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

/**
 * Starts and ends the server-side session for a principal (identity-and-sessions spec). Every
 * sign-in path ends here, whichever provider established who the caller is.
 */
@Component
@RequiredArgsConstructor
public class SessionAuthentication {

    /** How recent a sign-in or step-up must be for an action that demands one (ADR-0103). */
    public static final Duration REAUTHENTICATION_WINDOW = Duration.ofMinutes(5);

    /** The session attribute holding the session's {@link SessionFacts}. */
    public static final String FACTS = SessionAuthentication.class.getName() + ".facts";

    /**
     * The session attribute holding when the user last did something (an {@link Instant}). It is
     * apart from the immutable {@link SessionFacts} so that moving it rewrites one small value, not
     * the whole record (ADR-0144).
     */
    public static final String LAST_ACTIVITY_AT = SessionAuthentication.class.getName() + ".lastActivityAt";

    /**
     * Prefix of every session attribute that holds a half-finished sign-in or step-up. A new login
     * clears them all, so a stale one can never complete a different attempt.
     */
    public static final String PENDING_PREFIX = "PENDING_";

    /** Held by a session whose user gave the right password and has yet to give a second factor; it has no principal. */
    public static final String PENDING_SECOND_FACTOR = PENDING_PREFIX + "SECOND_FACTOR";

    /** Held by a signed-in session whose user gave the right password for a step-up and has yet to give a second factor. */
    public static final String PENDING_STEP_UP = PENDING_PREFIX + "STEP_UP";

    /** How long a password stays good for the second factor that completes it. */
    public static final Duration PENDING_WINDOW = Duration.ofMinutes(5);

    /** A sign-in awaiting its second factor: who gave the right password, through which provider, and when. */
    public record PendingSecondFactor(UUID userId, String providerId, Instant at) implements Serializable {}

    /** A step-up awaiting its second factor. */
    public record PendingStepUp(UUID userId, Instant at) implements Serializable {}

    private final SecurityContextRepository securityContextRepository;
    private final CsrfTokenRepository csrfTokenRepository;
    private final InitialInstallers initialInstallers;
    private final SessionTerminator terminator;
    private final SessionLifetimes lifetimes;
    private final FindByIndexNameSessionRepository<? extends Session> store;
    private final ApplicationEventPublisher events;

    /**
     * Start of a password login: forget any earlier signed-in context, its facts and every pending
     * sign-in or step-up in the session, so nothing from before the attempt can carry into it.
     */
    public void clearForLogin(HttpServletRequest request, HttpServletResponse response) {
        SecurityContextHolder.clearContext();
        securityContextRepository.saveContext(SecurityContextHolder.createEmptyContext(), request, response);
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(FACTS);
            session.removeAttribute(LAST_ACTIVITY_AT);
        }
        clearPending(request);
    }

    /** Forget every half-finished sign-in or step-up in the session. */
    public void clearPending(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            Collections.list(session.getAttributeNames()).stream()
                    .filter(name -> name.startsWith(PENDING_PREFIX))
                    .forEach(session::removeAttribute);
        }
    }

    /**
     * The right password was given for an account with a second factor: the session gets a new id
     * and remembers who, and stays without a principal, so the password alone opens nothing. It
     * expires with {@link #PENDING_WINDOW}.
     */
    public void awaitSecondFactor(
            UUID userId, String providerId, HttpServletRequest request, HttpServletResponse response) {
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        HttpSession session = request.getSession();
        session.setAttribute(PENDING_SECOND_FACTOR, new PendingSecondFactor(userId, providerId, Instant.now()));
        session.setMaxInactiveInterval((int) PENDING_WINDOW.toSeconds());
        reissueCsrfToken(request, response);
    }

    /** The sign-in this session is waiting to complete, when there is one and it is not too old. */
    public Optional<PendingSecondFactor> pendingSecondFactor(HttpServletRequest request) {
        return pending(request, PENDING_SECOND_FACTOR, PendingSecondFactor.class)
                .filter(p -> current(p.at()));
    }

    /** The signed-in user gave the right password for a step-up; the second factor completes it. */
    public void awaitStepUp(UUID userId, HttpServletRequest request) {
        request.getSession().setAttribute(PENDING_STEP_UP, new PendingStepUp(userId, Instant.now()));
    }

    /** The step-up this session is waiting to complete, when there is one and it is not too old. */
    public Optional<PendingStepUp> pendingStepUp(HttpServletRequest request) {
        return pending(request, PENDING_STEP_UP, PendingStepUp.class).filter(p -> current(p.at()));
    }

    private static <T> Optional<T> pending(HttpServletRequest request, String attribute, Class<T> type) {
        HttpSession session = request.getSession(false);
        return session == null ? Optional.empty() : Optional.ofNullable(type.cast(session.getAttribute(attribute)));
    }

    private static boolean current(Instant at) {
        return at.isAfter(Instant.now().minus(PENDING_WINDOW));
    }

    /**
     * Put the principal in the session. The framework's load-only
     * {@code SecurityContextHolderFilter} does not save a programmatically established
     * context, so it is saved explicitly. A session that existed before sign-in gets a new id,
     * so an identifier planted or observed before authentication never becomes authenticated
     * (session fixation); local login is a controller, so the filter chain's own fixation
     * strategy never runs for it.
     */
    public void establish(
            StudioPrincipal principal, SessionFacts facts, HttpServletRequest request, HttpServletResponse response) {
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        var authentication =
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        HttpSession session = request.getSession();
        session.setAttribute(FACTS, facts);
        // Establishing a session is itself activity. The session's sign-in time is not: a re-established
        // session (a password change) can be hours old, and would look idle at its very next request.
        session.setAttribute(LAST_ACTIVITY_AT, Instant.now());
        session.setMaxInactiveInterval(maxInactiveSeconds());
        reissueCsrfToken(request, response);
        initialInstallers.grantIfNoneYet(principal.userId());
    }

    /**
     * The user changed their password: a new session id and principal, with the session's facts
     * carried over unchanged (a password change is not a step-up), and every other session of the
     * user ends once the surrounding transaction commits. The session being replaced is kept out of
     * that as well as the new one, because the store still holds the old id until the response.
     */
    public void reestablishEndingOthers(
            StudioPrincipal principal, HttpServletRequest request, HttpServletResponse response) {
        SessionFacts facts = facts(request)
                .orElseThrow(() -> new AccessDeniedException("Change the password from a signed-in session"));
        String before = request.getSession().getId();
        establish(principal, facts, request, response);
        terminator.endSessionsOfExcept(
                principal.getUsername(), Set.of(before, request.getSession().getId()));
    }

    /**
     * The signed-in caller proved who they are again (step-up): a new session id, so an identifier
     * observed before the step-up is not the one that carries it, and a fresh authentication time.
     */
    public void reauthenticated(HttpServletRequest request) {
        SessionFacts facts = facts(request).orElseThrow(() -> new IllegalStateException("No session to step up"));
        request.changeSessionId();
        request.getSession().setAttribute(FACTS, facts.withAuthenticatedAt(Instant.now()));
    }

    /** Step-up for an account with a second factor: the password was checked earlier, and {@code method} completes it. */
    public void reauthenticated(HttpServletRequest request, SessionFacts.Method method) {
        SessionFacts facts = facts(request).orElseThrow(() -> new IllegalStateException("No session to step up"));
        Instant now = Instant.now();
        request.changeSessionId();
        HttpSession session = request.getSession();
        session.removeAttribute(PENDING_STEP_UP);
        session.setAttribute(FACTS, facts.withAuthenticatedAt(now).withMfaVerified(now, method));
    }

    /**
     * The user enrolled a second factor and proved it with {@code method}: a new session id and a
     * principal without the enrolment restriction. Authentication counts as fresh only if it already
     * was, because entering a code for a factor just created proves nothing about who holds the
     * password, so a stale session cannot use enrolling to skip a step-up.
     */
    public void reestablishAfterEnrolment(
            StudioPrincipal principal,
            SessionFacts.Method method,
            HttpServletRequest request,
            HttpServletResponse response) {
        SessionFacts facts = facts(request)
                .orElseThrow(() -> new AccessDeniedException("Enrol a second factor from a signed-in session"));
        Instant now = Instant.now();
        boolean fresh = recentlyAuthenticated(request);
        establish(
                principal.withSecondFactorEnrolmentRequired(false),
                (fresh ? facts.withAuthenticatedAt(now) : facts).withMfaVerified(now, method),
                request,
                response);
    }

    /** The facts of this session; empty without a session or before sign-in. */
    public Optional<SessionFacts> facts(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session == null ? Optional.empty() : Optional.ofNullable((SessionFacts) session.getAttribute(FACTS));
    }

    /** When the user last did something in this session; its sign-in time until they do. */
    public Optional<Instant> lastActivityAt(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return Optional.empty();
        }
        Instant last = (Instant) session.getAttribute(LAST_ACTIVITY_AT);
        return last != null ? Optional.of(last) : facts(request).map(SessionFacts::signedInAt);
    }

    /** Whether a session with these facts and last activity is past its absolute lifetime or idle timeout. */
    public boolean expired(SessionFacts facts, Instant lastActivityAt) {
        Instant now = Instant.now();
        return now.isAfter(facts.signedInAt().plus(lifetimes.absoluteLifetime()))
                || now.isAfter(lastActivityAt.plus(lifetimes.idleTimeout()));
    }

    /**
     * Whether the stored session still counts as signed in: it exists, has facts and is within both
     * lifetimes. Reads the store, so it sees a session ended on another instance and one that timed
     * out, and it never counts as activity.
     */
    public boolean isLive(String sessionId) {
        Session stored = store.findById(sessionId);
        SessionFacts facts = stored == null ? null : stored.getAttribute(FACTS);
        if (facts == null) {
            return false;
        }
        Instant last = stored.getAttribute(LAST_ACTIVITY_AT);
        return !expired(facts, last != null ? last : facts.signedInAt());
    }

    /** Record that the user did something now. */
    public void recordActivity(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.setAttribute(LAST_ACTIVITY_AT, Instant.now());
        }
    }

    /**
     * Spring Session's own inactivity timeout, set to the idle timeout as a backstop: it counts any
     * request, so it only ever ends a session sooner than the server would have looked, never later.
     */
    public void syncMaxInactiveInterval(HttpSession session) {
        int seconds = maxInactiveSeconds();
        if (session.getMaxInactiveInterval() != seconds) {
            session.setMaxInactiveInterval(seconds);
        }
    }

    private int maxInactiveSeconds() {
        return (int) Math.min(lifetimes.idleTimeout().toSeconds(), Integer.MAX_VALUE);
    }

    /** When this session last signed in or stepped up in full; empty when it is not fresh. */
    public Optional<Instant> authenticatedAt(HttpServletRequest request) {
        return facts(request).map(SessionFacts::authenticatedAt);
    }

    /** Whether this session signed in or stepped up within {@link #REAUTHENTICATION_WINDOW}. */
    public boolean recentlyAuthenticated(HttpServletRequest request) {
        return authenticatedAt(request)
                .map(at -> at.isAfter(Instant.now().minus(REAUTHENTICATION_WINDOW)))
                .orElse(false);
    }

    /** Clear the session and its security context. */
    public void end(HttpServletRequest request, HttpServletResponse response) {
        SecurityContextHolder.getContext().setAuthentication(null);
        securityContextRepository.saveContext(SecurityContextHolder.createEmptyContext(), request, response);
        var session = request.getSession(false);
        if (session != null) {
            String id = session.getId();
            session.invalidate();
            events.publishEvent(new SessionEnded(id));
        }
        reissueCsrfToken(request, response);
    }

    /**
     * {@code CsrfAuthenticationStrategy} and {@code CsrfLogoutHandler} clear the previous CSRF
     * cookie on login and logout — a documented SPA gotcha (design.md decision 1) — so both
     * paths save a fresh one explicitly rather than waiting for lazy regeneration.
     */
    private void reissueCsrfToken(HttpServletRequest request, HttpServletResponse response) {
        CsrfToken token = csrfTokenRepository.generateToken(request);
        csrfTokenRepository.saveToken(token, request, response);
    }
}
