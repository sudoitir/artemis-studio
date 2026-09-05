package io.github.sudoitir.artemisstudio.web;

import io.github.sudoitir.artemisstudio.security.StudioPrincipal;
import io.github.sudoitir.artemisstudio.service.AuthService;
import io.github.sudoitir.artemisstudio.web.dto.AuthViews.ChangePasswordRequest;
import io.github.sudoitir.artemisstudio.web.dto.AuthViews.GrantView;
import io.github.sudoitir.artemisstudio.web.dto.AuthViews.LoginRequest;
import io.github.sudoitir.artemisstudio.web.dto.AuthViews.MeView;
import io.github.sudoitir.artemisstudio.web.dto.AuthViews.ProviderView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Login, logout, current identity, and self-service password change
 * (identity-and-sessions spec, ADR-0037). {@code /login} is the one mutating
 * endpoint reachable with no session — see {@code SecurityConfig}'s allow-list.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final ObjectProvider<ClientRegistrationRepository> clientRegistrations;

    /**
     * Public, unauthenticated: the login screen needs to know whether to show
     * an SSO entry point before the user has any session at all (ADR-0040).
     * {@link ClientRegistrationRepository} has no listing method on its base
     * interface, so registrations are only discoverable when the concrete
     * instance is also {@link Iterable} — true for the Boot auto-configured
     * {@code InMemoryClientRegistrationRepository}, which is the only kind this
     * app creates.
     */
    @GetMapping("/providers")
    public List<ProviderView> providers() {
        ClientRegistrationRepository repository = clientRegistrations.getIfAvailable();
        if (!(repository instanceof Iterable<?> registrations)) {
            return List.of();
        }
        return java.util.stream.StreamSupport.stream(registrations.spliterator(), false)
                .map(r -> (ClientRegistration) r)
                .map(r -> new ProviderView(
                        r.getRegistrationId(), r.getClientName(), "/oauth2/authorization/" + r.getRegistrationId()))
                .toList();
    }

    @PostMapping("/login")
    public MeView login(@Valid @RequestBody LoginRequest request, HttpServletRequest req, HttpServletResponse resp) {
        return toView(authService.login(request.username(), request.password(), req, resp));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest req, HttpServletResponse resp) {
        authService.logout(req, resp);
    }

    @GetMapping("/me")
    public MeView me(@AuthenticationPrincipal StudioPrincipal principal) {
        return toView(principal);
    }

    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(
            @AuthenticationPrincipal StudioPrincipal principal,
            @Valid @RequestBody ChangePasswordRequest request,
            HttpServletRequest req,
            HttpServletResponse resp) {
        authService.changePassword(principal, request.currentPassword(), request.newPassword(), req, resp);
    }

    private static MeView toView(StudioPrincipal principal) {
        var grants = principal.grantList().stream()
                .map(g -> new GrantView(
                        g.scopeType().name(),
                        g.scopeId(),
                        g.permissions().stream().sorted().toList()))
                .toList();
        return new MeView(principal.userId(), principal.getUsername(), principal.mustChangePassword(), grants);
    }
}
