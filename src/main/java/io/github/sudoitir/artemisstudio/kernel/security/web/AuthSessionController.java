package io.github.sudoitir.artemisstudio.kernel.security.web;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.github.sudoitir.artemisstudio.kernel.plugin.IdentityProviderListing;
import io.github.sudoitir.artemisstudio.kernel.security.StudioPrincipal;
import io.github.sudoitir.artemisstudio.kernel.security.internal.LoginService;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sign-in, sign-out, the current identity and the providers to sign in with
 * (identity-and-sessions spec). {@code /login} and {@code /providers} are the only endpoints
 * reachable with no session — see {@code SecurityConfig}'s allow-list.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthSessionController {

    private final LoginService logins;
    private final IdentityProviderListing providers;

    public record LoginRequest(
            @Schema(nullable = true, description = "The credential provider to sign in with. Omit for local.")
            String provider,

            @NotBlank String username,
            @NotBlank String password) {}

    public record GrantView(
            @Schema(requiredMode = REQUIRED) String scopeType,
            @Schema(nullable = true) UUID scopeId,
            @Schema(requiredMode = REQUIRED) List<String> permissions) {}

    public record MeView(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED) String username,
            @Schema(requiredMode = REQUIRED) boolean mustChangePassword,
            @Schema(requiredMode = REQUIRED) List<GrantView> grants) {

        public static MeView of(StudioPrincipal principal) {
            var grants = principal.grantList().stream()
                    .map(g -> new GrantView(
                            g.scopeType().name(),
                            g.scopeId(),
                            g.permissions().stream().sorted().toList()))
                    .toList();
            return new MeView(principal.userId(), principal.getUsername(), principal.mustChangePassword(), grants);
        }
    }

    public record IdentityProviderView(
            @Schema(requiredMode = REQUIRED) String id,

            @Schema(
                    requiredMode = REQUIRED,
                    description = "CREDENTIAL for a username and password form; REDIRECT for sign-in elsewhere.",
                    allowableValues = {"CREDENTIAL", "REDIRECT"})
            String kind,

            @Schema(requiredMode = REQUIRED) String label,

            @Schema(nullable = true, description = "Where a redirect provider's sign-in begins.")
            String startPath) {}

    /** Public: the login screen is built from this before any session exists. */
    @GetMapping("/providers")
    public List<IdentityProviderView> providers() {
        return providers.providers().stream()
                .map(p -> new IdentityProviderView(p.id(), p.kind(), p.label(), p.startPath()))
                .toList();
    }

    @PostMapping("/login")
    public MeView login(@Valid @RequestBody LoginRequest request, HttpServletRequest req, HttpServletResponse resp) {
        return MeView.of(logins.login(request.provider(), request.username(), request.password(), req, resp));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest req, HttpServletResponse resp) {
        logins.logout(req, resp);
    }

    @GetMapping("/me")
    public MeView me(@AuthenticationPrincipal StudioPrincipal principal) {
        return MeView.of(principal);
    }
}
