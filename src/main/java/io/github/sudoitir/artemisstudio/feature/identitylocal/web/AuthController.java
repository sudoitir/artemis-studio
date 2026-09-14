package io.github.sudoitir.artemisstudio.feature.identitylocal.web;

import io.github.sudoitir.artemisstudio.feature.identitylocal.AuthService;
import io.github.sudoitir.artemisstudio.feature.identitylocal.web.AuthViews.ChangePasswordRequest;
import io.github.sudoitir.artemisstudio.kernel.security.StudioPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Self-service password change for a local account (identity-and-sessions spec, ADR-0037). */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(
            @AuthenticationPrincipal StudioPrincipal principal,
            @Valid @RequestBody ChangePasswordRequest request,
            HttpServletRequest req,
            HttpServletResponse resp) {
        authService.changePassword(principal, request.currentPassword(), request.newPassword(), req, resp);
    }
}
