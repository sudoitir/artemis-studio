package io.github.sudoitir.artemisstudio.kernel.settings.web;

import io.github.sudoitir.artemisstudio.kernel.settings.SettingsService;
import io.github.sudoitir.artemisstudio.kernel.settings.web.SettingsViews.ChangePreview;
import io.github.sudoitir.artemisstudio.kernel.settings.web.SettingsViews.ChangeSetRequest;
import io.github.sudoitir.artemisstudio.kernel.settings.web.SettingsViews.SettingsResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator-tunable configuration ({@code /api/v1/settings}). {@code GET} returns every key's effective value plus
 * whether it is a stored override and any change waiting for approval; {@code POST /changes} applies a change set
 * (settings to set, settings to reset) together or not at all, and {@code POST /changes/preview} says whether it
 * would run, be held for approval or be denied. A held change set answers {@code 202} through the approval gate. An
 * invalid value is a {@code 400} naming each invalid setting.
 */
@RestController
@RequestMapping("/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final SettingsService settings;

    @GetMapping
    public SettingsResponse list() {
        return new SettingsResponse(settings.effective());
    }

    @PostMapping("/changes")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void apply(@Valid @RequestBody ChangeSetRequest request) {
        settings.apply(request.toChanges());
    }

    @PostMapping("/changes/preview")
    public ChangePreview preview(@Valid @RequestBody ChangeSetRequest request) {
        return ChangePreview.of(settings.preview(request.toChanges()));
    }
}
