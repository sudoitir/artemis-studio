package io.github.sudoitir.artemisstudio.feature.apitokens.web;

import io.github.sudoitir.artemisstudio.feature.apitokens.ApiTokenService;
import io.github.sudoitir.artemisstudio.feature.apitokens.web.TokenViews.TokenView;
import io.github.sudoitir.artemisstudio.feature.apitokens.web.TokenViews.UsageView;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Every user's API tokens, metadata only, for holders of {@code token:admin} (api-tokens spec,
 * ADR-0136): so a leaked token can be revoked without its owner. No mint and no rotate here.
 */
@RestController
@RequestMapping("/admin/tokens")
@RequiredArgsConstructor
@PreAuthorize("@perm.can(T(io.github.sudoitir.artemisstudio.feature.apitokens.TokenPermissions).TOKEN_ADMIN)")
public class AdminTokensController {

    private final ApiTokenService tokens;
    private final TokenViewAssembler views;

    @GetMapping
    public List<TokenView> list() {
        return views.views(tokens.listAll());
    }

    @GetMapping("/{tokenId}/usage")
    public UsageView usage(@PathVariable UUID tokenId, @RequestParam(defaultValue = "7") int days) {
        return TokenViewAssembler.usage(tokens.usageAny(tokenId, TokenViewAssembler.period(days)));
    }

    @DeleteMapping("/{tokenId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID tokenId) {
        tokens.revokeAny(tokenId);
    }
}
