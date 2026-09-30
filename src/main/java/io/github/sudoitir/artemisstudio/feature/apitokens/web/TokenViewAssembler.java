package io.github.sudoitir.artemisstudio.feature.apitokens.web;

import io.github.sudoitir.artemisstudio.feature.apitokens.ApiTokenService;
import io.github.sudoitir.artemisstudio.feature.apitokens.TokenUsage;
import io.github.sudoitir.artemisstudio.feature.apitokens.internal.persistence.ApiTokenEntity;
import io.github.sudoitir.artemisstudio.feature.apitokens.web.TokenViews.TokenGrantView;
import io.github.sudoitir.artemisstudio.feature.apitokens.web.TokenViews.TokenView;
import io.github.sudoitir.artemisstudio.feature.apitokens.web.TokenViews.UsageDayView;
import io.github.sudoitir.artemisstudio.feature.apitokens.web.TokenViews.UsageView;
import io.github.sudoitir.artemisstudio.kernel.security.UserAccounts;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Builds token views for the owner's and the administrators' endpoints alike. */
@Component
@RequiredArgsConstructor
class TokenViewAssembler {

    private static final Set<Integer> PERIODS = Set.of(1, 7, 30);

    private final ApiTokenService tokens;
    private final UserAccounts accounts;

    List<TokenView> views(List<ApiTokenEntity> list) {
        Map<UUID, String> owners = list.stream()
                .map(ApiTokenEntity::getUserId)
                .distinct()
                .collect(Collectors.toMap(Function.identity(), this::username));
        return list.stream().map(t -> view(t, owners.get(t.getUserId()))).toList();
    }

    TokenView view(ApiTokenEntity t) {
        return view(t, username(t.getUserId()));
    }

    private TokenView view(ApiTokenEntity t, String owner) {
        return new TokenView(
                t.getId(),
                t.getName(),
                owner,
                t.getPrefix(),
                tokens.effectiveExpiry(t),
                t.getLastUsedAt(),
                t.getRevokedAt(),
                t.getCreatedAt(),
                t.getPreviousValidUntil(),
                tokens.grantsOf(t.getId()).stream()
                        .flatMap(g -> g.permissions().stream()
                                .map(a -> new TokenGrantView(a, g.scopeType().name(), g.scopeId())))
                        .toList(),
                List.copyOf(t.getMcpTools()),
                t.getRevokedAt() == null && tokens.isStale(t));
    }

    private String username(UUID userId) {
        return accounts.byId(userId).map(UserAccounts.Account::username).orElse(userId.toString());
    }

    static int period(int days) {
        if (!PERIODS.contains(days)) {
            throw new IllegalArgumentException("days must be 1, 7 or 30");
        }
        return days;
    }

    static UsageView usage(TokenUsage.Summary s) {
        return new UsageView(
                s.days(),
                s.totals().requests(),
                s.totals().denied(),
                s.totals().limited(),
                s.totals().errors(),
                s.perDay().stream()
                        .map(d -> new UsageDayView(d.day(), d.requests(), d.denied(), d.limited(), d.errors()))
                        .toList());
    }
}
