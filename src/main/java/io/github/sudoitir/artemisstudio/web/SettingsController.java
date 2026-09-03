package io.github.sudoitir.artemisstudio.web;

import io.github.sudoitir.artemisstudio.service.SettingsService;
import io.github.sudoitir.artemisstudio.web.dto.SettingsViews.SettingsResponse;
import io.github.sudoitir.artemisstudio.web.dto.SettingsViews.UpdateSettingRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator-tunable configuration ({@code /api/v1/settings}). {@code GET} returns
 * every key's effective value plus whether it is a stored override; {@code PUT}
 * sets one; {@code DELETE} clears the override back to the {@code application.yml}
 * default. An invalid value is a {@code 400} through {@link ApiExceptionHandler}.
 */
@RestController
@RequestMapping("/api/v1/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final SettingsService settings;

    @GetMapping
    public SettingsResponse list() {
        return new SettingsResponse(settings.effective());
    }

    @PutMapping("/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void put(@PathVariable String key, @Valid @RequestBody UpdateSettingRequest request) {
        settings.put(key, request.value());
    }

    @DeleteMapping("/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset(@PathVariable String key) {
        settings.reset(key);
    }
}
