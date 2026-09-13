package io.github.sudoitir.artemisstudio.feature.identitylocal.web;

import jakarta.validation.constraints.NotBlank;

/** The password-change request (identity-and-sessions spec). */
public final class AuthViews {

    private AuthViews() {}

    public record ChangePasswordRequest(
            @NotBlank String currentPassword, @NotBlank String newPassword) {}
}
