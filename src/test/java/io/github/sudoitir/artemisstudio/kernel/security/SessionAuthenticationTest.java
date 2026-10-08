package io.github.sudoitir.artemisstudio.kernel.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.kernel.replica.StudioBus;
import io.github.sudoitir.artemisstudio.kernel.security.internal.InitialInstallers;
import io.github.sudoitir.artemisstudio.kernel.security.internal.SessionTerminator;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** What a session holds once someone is signed in to it, whichever way they signed in. */
class SessionAuthenticationTest {

    private final SessionLifetimes lifetimes = mock(SessionLifetimes.class);

    @SuppressWarnings("unchecked")
    private final SessionAuthentication sessions = new SessionAuthentication(
            mock(SecurityContextRepository.class),
            mock(CsrfTokenRepository.class),
            mock(InitialInstallers.class),
            mock(SessionTerminator.class),
            lifetimes,
            mock(FindByIndexNameSessionRepository.class),
            mock(ApplicationEventPublisher.class),
            mock(StudioBus.class));

    /**
     * A sign-in that does not go through the password login (single sign-on) never clears the session
     * first, so establishing the session is what forgets a half-finished sign-in, step-up or passkey
     * challenge left there by an earlier user.
     */
    @Test
    void establishingASessionForgetsEveryHalfFinishedSignIn() {
        when(lifetimes.idleTimeout()).thenReturn(Duration.ofMinutes(30));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login/oauth2/code/corp");
        UUID someoneElse = UUID.randomUUID();
        var session = request.getSession();
        session.setAttribute(
                SessionAuthentication.PENDING_SECOND_FACTOR,
                new SessionAuthentication.PendingSecondFactor(someoneElse, "local", Instant.now(), 1L));
        session.setAttribute(
                SessionAuthentication.PENDING_STEP_UP,
                new SessionAuthentication.PendingStepUp(someoneElse, Instant.now()));
        session.setAttribute(
                SessionAuthentication.PENDING_PASSKEY,
                new SessionAuthentication.PendingPasskey(someoneElse, "challenge", Instant.now()));
        session.setAttribute("unrelated", "kept");
        StudioPrincipal principal = new StudioPrincipal(UUID.randomUUID(), "sso-user", Set.of(), false);

        try {
            sessions.establish(principal, SessionFacts.signedIn(request), request, new MockHttpServletResponse());
        } finally {
            SecurityContextHolder.clearContext();
        }

        assertThat(Collections.list(request.getSession().getAttributeNames()))
                .noneMatch(name -> name.startsWith(SessionAuthentication.PENDING_PREFIX))
                .contains("unrelated", SessionAuthentication.FACTS_ATTRIBUTE);
    }

    private static void signedInAs(StudioPrincipal principal, MockHttpServletRequest request) {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private static void clear() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void theCurrentSessionsFactsAreThoseOfTheRequestsSession() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/anything");
        SessionFacts facts = SessionFacts.signedInWithSecondFactor(request, SessionFacts.Method.TOTP);
        request.getSession().setAttribute(SessionAuthentication.FACTS_ATTRIBUTE, facts);
        signedInAs(new StudioPrincipal(UUID.randomUUID(), "ann", Set.of(), false), request);

        try {
            assertThat(sessions.current()).contains(facts);
        } finally {
            clear();
        }
    }

    @Test
    void anApiTokenAndAThreadWithNoRequestHaveNoSession() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/anything");
        request.getSession().setAttribute(SessionAuthentication.FACTS_ATTRIBUTE, SessionFacts.signedIn(request));
        TokenPrincipal token =
                new TokenPrincipal(UUID.randomUUID(), "ann", Set.of(), UUID.randomUUID(), "ci", Set.of());
        signedInAs(token, request);

        try {
            assertThat(sessions.current()).isEmpty();
            RequestContextHolder.resetRequestAttributes();
            signedInAs(new StudioPrincipal(UUID.randomUUID(), "ann", Set.of(), false), request);
            RequestContextHolder.resetRequestAttributes();
            assertThat(sessions.current()).isEmpty();
        } finally {
            clear();
        }
    }
}
