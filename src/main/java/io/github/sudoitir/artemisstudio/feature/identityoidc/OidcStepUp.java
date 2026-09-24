package io.github.sudoitir.artemisstudio.feature.identityoidc;

import io.github.sudoitir.artemisstudio.kernel.security.SessionAuthentication;
import io.github.sudoitir.artemisstudio.kernel.security.StudioPrincipal;
import io.github.sudoitir.artemisstudio.kernel.security.UserAccounts;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;

/**
 * Step-up for a single-sign-on user (ADR-0103). {@code /oauth2/authorization/<id>?stepup&returnTo=/x}
 * from a signed-in user asks the provider for a fresh sign-in ({@code prompt=login},
 * {@code max_age=300}) and remembers who asked; {@link #verify} then accepts the returning identity
 * only when it is that same user and its {@code auth_time} is fresh. A provider that omits
 * {@code auth_time} fails the step-up — the one claim that proves a fresh sign-in is not optional.
 */
@Component
@RequiredArgsConstructor
class OidcStepUp {

    static final Duration MAX_AGE = SessionAuthentication.REAUTHENTICATION_WINDOW;

    /** Tolerated clock difference between Studio and the provider. */
    private static final Duration SKEW = Duration.ofSeconds(30);

    private static final String PENDING = OidcStepUp.class.getName() + ".pending";

    private final UserAccounts accounts;

    record Pending(UUID userId, String registrationId, String subject, String returnTo, Instant startedAt)
            implements Serializable {}

    OAuth2AuthorizationRequestResolver resolver(ClientRegistrationRepository registrations) {
        var defaults = new DefaultOAuth2AuthorizationRequestResolver(
                registrations, DefaultOAuth2AuthorizationRequestResolver.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI);
        return new OAuth2AuthorizationRequestResolver() {
            @Override
            public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
                return stepUp(request, defaults.resolve(request));
            }

            @Override
            public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String registrationId) {
                return stepUp(request, defaults.resolve(request, registrationId));
            }
        };
    }

    /**
     * Marks the request as a step-up when it asks for one and a signed-in single-sign-on user of
     * this same registration made it; anything else is an ordinary sign-in.
     */
    private OAuth2AuthorizationRequest stepUp(HttpServletRequest request, OAuth2AuthorizationRequest resolved) {
        if (resolved == null || request.getParameter("stepup") == null) {
            return resolved;
        }
        String registrationId = (String) resolved.getAttributes().get("registration_id");
        Optional<UserAccounts.Account> account = currentUser().flatMap(p -> accounts.byId(p.userId()));
        if (account.isEmpty()
                || registrationId == null
                || !registrationId.equals(account.get().providerId())
                || account.get().externalSubject() == null) {
            return resolved;
        }
        request.getSession()
                .setAttribute(
                        PENDING,
                        new Pending(
                                account.get().id(),
                                registrationId,
                                account.get().externalSubject(),
                                safeReturnTo(request.getParameter("returnTo")),
                                Instant.now()));
        return OAuth2AuthorizationRequest.from(resolved)
                .additionalParameters(params -> {
                    params.put("prompt", "login");
                    params.put("max_age", String.valueOf(MAX_AGE.toSeconds()));
                })
                .build();
    }

    /** The pending step-up in this session, removed as it is read: one answer per request. */
    Optional<Pending> takePending(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || !(session.getAttribute(PENDING) instanceof Pending pending)) {
            return Optional.empty();
        }
        session.removeAttribute(PENDING);
        return Optional.of(pending);
    }

    /** Why the returning identity does not complete {@code pending}, or empty when it does. */
    static Optional<String> verify(Pending pending, String registrationId, String subject, Instant authTime) {
        if (!pending.registrationId().equals(registrationId)
                || !pending.subject().equals(subject)) {
            return Optional.of("a different account signed in");
        }
        if (authTime == null) {
            return Optional.of("the provider did not say when you signed in (auth_time)");
        }
        Instant now = Instant.now();
        if (authTime.isBefore(pending.startedAt().minus(SKEW)) || authTime.isBefore(now.minus(MAX_AGE))) {
            return Optional.of("the provider did not ask you to sign in again");
        }
        return Optional.empty();
    }

    /** Only a same-origin path: never another host, never a scheme-relative {@code //host}. */
    static String safeReturnTo(String returnTo) {
        if (returnTo == null
                || !returnTo.startsWith("/")
                || returnTo.startsWith("//")
                || returnTo.contains("\\")
                || returnTo.chars().anyMatch(Character::isISOControl)) {
            return "/";
        }
        return returnTo;
    }

    private static Optional<StudioPrincipal> currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof StudioPrincipal p ? Optional.of(p) : Optional.empty();
    }
}
