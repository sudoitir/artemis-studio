package io.github.sudoitir.artemisstudio.feature.apitokens.web;

import io.github.sudoitir.artemisstudio.feature.apitokens.ApiTokenService;
import io.github.sudoitir.artemisstudio.feature.apitokens.ApiTokensSettings;
import io.github.sudoitir.artemisstudio.feature.apitokens.web.TokenViews.CreateTokenRequest;
import io.github.sudoitir.artemisstudio.feature.apitokens.web.TokenViews.CreatedTokenView;
import io.github.sudoitir.artemisstudio.feature.apitokens.web.TokenViews.TokenPolicyView;
import io.github.sudoitir.artemisstudio.feature.apitokens.web.TokenViews.TokenView;
import io.github.sudoitir.artemisstudio.feature.apitokens.web.TokenViews.UsageView;
import io.github.sudoitir.artemisstudio.kernel.security.Grant;
import io.github.sudoitir.artemisstudio.kernel.security.ScopeIds;
import io.github.sudoitir.artemisstudio.kernel.security.StudioPrincipal;
import io.github.sudoitir.artemisstudio.kernel.security.TokenPrincipal;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsService;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's own API tokens (api-tokens spec). Minting and rotation happen only here; a token
 * is narrowable only to a subset of its own owner's grants. Administrators see and revoke every
 * user's tokens through {@link AdminTokensController}.
 *
 * <p>Only a signed-in session manages keys. A key that could mint or rotate keys would escape its
 * own narrowed grants and tool allow-list, and a leaked one could take over its owner's other keys.
 */
@RestController
@RequestMapping("/api/v1/tokens")
@RequiredArgsConstructor
public class TokensController {

    private final ApiTokenService tokens;
    private final TokenViewAssembler views;
    private final SettingsService settings;

    @GetMapping
    public List<TokenView> list(@AuthenticationPrincipal StudioPrincipal principal) {
        requireSession(principal);
        return views.views(tokens.listFor(principal.userId()));
    }

    @GetMapping("/policy")
    public TokenPolicyView policy() {
        return new TokenPolicyView(
                tokens.maxLifetime().toString(),
                Instant.now().plus(tokens.maxLifetime()),
                settings.duration(ApiTokensSettings.ROTATION_OVERLAP).toString());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedTokenView create(
            @AuthenticationPrincipal StudioPrincipal principal, @Valid @RequestBody CreateTokenRequest request) {
        requireSession(principal);
        List<Grant> requested = request.grants().stream()
                .map(g -> new Grant(
                        Grant.ScopeType.valueOf(g.scopeType()),
                        g.scopeId() != null ? g.scopeId() : ScopeIds.GLOBAL,
                        Set.of(g.action())))
                .toList();
        var minted = tokens.mint(
                principal.userId(),
                request.name(),
                request.expiresAt(),
                requested,
                request.mcpTools() == null ? List.of() : request.mcpTools());
        return new CreatedTokenView(views.view(minted.entity()), minted.plaintext());
    }

    @PostMapping("/{tokenId}/rotate")
    public CreatedTokenView rotate(@AuthenticationPrincipal StudioPrincipal principal, @PathVariable UUID tokenId) {
        requireSession(principal);
        var rotated = tokens.rotate(principal.userId(), tokenId);
        return new CreatedTokenView(views.view(rotated.entity()), rotated.plaintext());
    }

    @GetMapping("/{tokenId}/usage")
    public UsageView usage(
            @AuthenticationPrincipal StudioPrincipal principal,
            @PathVariable UUID tokenId,
            @RequestParam(defaultValue = "7") int days) {
        requireSession(principal);
        return TokenViewAssembler.usage(tokens.usage(principal.userId(), tokenId, TokenViewAssembler.period(days)));
    }

    @DeleteMapping("/{tokenId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@AuthenticationPrincipal StudioPrincipal principal, @PathVariable UUID tokenId) {
        requireSession(principal);
        tokens.revoke(principal.userId(), tokenId);
    }

    private static void requireSession(StudioPrincipal principal) {
        if (principal instanceof TokenPrincipal) {
            throw new AccessDeniedException("API keys are managed from a signed-in session, not with a key");
        }
    }
}
