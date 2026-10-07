package io.github.sudoitir.artemisstudio.kernel.settings.web;

import io.github.sudoitir.artemisstudio.kernel.gate.HeldResponse;
import io.github.sudoitir.artemisstudio.kernel.settings.SecretRotationService;
import io.github.sudoitir.artemisstudio.kernel.settings.web.SecretsViews.RotationView;
import io.github.sudoitir.artemisstudio.kernel.settings.web.SecretsViews.SecretsStatus;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The secret provider's status and key rotation ({@code /api/v1/settings/secrets}). Starting a rotation needs
 * {@code settings:write} and a fresh authentication; a refusal is a {@code 403}, no newer key or a running rotation
 * a {@code 409}.
 */
@RestController
@RequestMapping("/settings/secrets")
@RequiredArgsConstructor
public class SecretsController {

    private final SecretRotationService secrets;

    @GetMapping
    public SecretsStatus status() {
        return SecretsViews.of(secrets.status());
    }

    /** Starts the rotation, or, when an approval provider holds it, answers 202 with the held request and starts nothing. */
    @HeldResponse
    @PostMapping("/rotations")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RotationView rotate(HttpServletRequest request) {
        return RotationView.of(secrets.start(request));
    }
}
