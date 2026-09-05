package io.github.sudoitir.artemisstudio.web;

import io.github.sudoitir.artemisstudio.persist.ApiTokenEntity;
import io.github.sudoitir.artemisstudio.security.ApiTokenService;
import io.github.sudoitir.artemisstudio.security.Grant;
import io.github.sudoitir.artemisstudio.security.StudioPrincipal;
import io.github.sudoitir.artemisstudio.web.dto.TokenViews.CreateTokenRequest;
import io.github.sudoitir.artemisstudio.web.dto.TokenViews.CreatedTokenView;
import io.github.sudoitir.artemisstudio.web.dto.TokenViews.TokenView;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Personal API tokens, always scoped to the caller's own account (api-tokens
 * spec) — there is no admin listing of other users' tokens; a token is
 * narrowable only to a subset of its own owner's grants.
 */
@RestController
@RequestMapping("/api/v1/tokens")
@RequiredArgsConstructor
public class TokensController {

    private final ApiTokenService tokens;

    @GetMapping
    public List<TokenView> list(@AuthenticationPrincipal StudioPrincipal principal) {
        return tokens.listFor(principal.userId()).stream()
                .map(TokensController::toView)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedTokenView create(
            @AuthenticationPrincipal StudioPrincipal principal, @Valid @RequestBody CreateTokenRequest request) {
        List<Grant> requested = request.grants().stream()
                .map(g -> new Grant(Grant.ScopeType.valueOf(g.scopeType()), g.scopeId(), Set.of(g.action())))
                .toList();
        var minted = tokens.mint(principal.userId(), request.name(), request.expiresAt(), requested);
        return new CreatedTokenView(toView(minted.entity()), minted.plaintext());
    }

    @DeleteMapping("/{tokenId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@AuthenticationPrincipal StudioPrincipal principal, @PathVariable UUID tokenId) {
        tokens.revoke(principal.userId(), tokenId);
    }

    private static TokenView toView(ApiTokenEntity t) {
        return new TokenView(
                t.getId(),
                t.getName(),
                t.getPrefix(),
                t.getExpiresAt(),
                t.getLastUsedAt(),
                t.getRevokedAt(),
                t.getCreatedAt());
    }
}
