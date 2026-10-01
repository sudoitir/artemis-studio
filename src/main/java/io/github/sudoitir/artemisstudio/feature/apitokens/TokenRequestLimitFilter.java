package io.github.sudoitir.artemisstudio.feature.apitokens;

import io.github.sudoitir.artemisstudio.kernel.core.Problems;
import io.github.sudoitir.artemisstudio.kernel.security.TokenPrincipal;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Rate and concurrency limits on token-authenticated requests, the MCP endpoint included, and the
 * count behind each token's usage summary (api-tokens spec, ADR-0136). Registered at the default
 * order, after the security chain, so it sees the principal. Browser sessions pass untouched: the
 * UI polls, and limiting it would only break the console.
 */
@Component
class TokenRequestLimitFilter extends OncePerRequestFilter {

    private final SettingsService settings;
    private final TokenUsage usage;
    private final JsonMapper json;
    private final RequestLimiter tokenWindows;
    private final RequestLimiter userWindows;

    TokenRequestLimitFilter(SettingsService settings, TokenUsage usage, JsonMapper json, JdbcTemplate jdbc) {
        this.settings = settings;
        this.usage = usage;
        this.json = json;
        this.tokenWindows = new RequestLimiter(jdbc);
        this.userWindows = new RequestLimiter(jdbc);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof TokenPrincipal token)) {
            chain.doFilter(request, response);
            return;
        }
        long now = System.currentTimeMillis();
        RequestLimiter.Decision perToken = tokenWindows.acquire(
                token.tokenId(), settings.intValue(ApiTokensSettings.TOKEN_REQUESTS_PER_MINUTE), now);
        RequestLimiter.Decision decision = perToken;
        if (perToken.allowed()) {
            RequestLimiter.Decision perUser = userWindows.acquire(
                    token.userId(), settings.intValue(ApiTokensSettings.USER_REQUESTS_PER_MINUTE), now);
            if (!perUser.allowed()) {
                // Refused by the owner's limit: the token's own allowance is not spent on it.
                tokenWindows.refund(token.tokenId(), now);
            }
            decision = tighter(perToken, perUser);
        }
        response.setHeader("RateLimit-Limit", Integer.toString(decision.limit()));
        response.setHeader("RateLimit-Remaining", Integer.toString(decision.remaining()));
        response.setHeader("RateLimit-Reset", Long.toString(decision.resetSeconds()));
        if (!decision.allowed()) {
            refuse(
                    response,
                    token,
                    decision.resetSeconds(),
                    "The request rate limit of this token or its owner is reached.");
            return;
        }
        if (!tokenWindows.enter(token.tokenId(), settings.intValue(ApiTokensSettings.TOKEN_CONCURRENCY))) {
            refuse(response, token, 1, "Too many requests with this token are in flight at once.");
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            tokenWindows.exit(token.tokenId());
            usage.recordRequest(token.tokenId(), outcome(request, response.getStatus()));
        }
    }

    /** The decision whose allowance is smaller, or the refusal if either refuses. */
    private static RequestLimiter.Decision tighter(RequestLimiter.Decision a, RequestLimiter.Decision b) {
        if (!b.allowed()) {
            return b;
        }
        return b.remaining() < a.remaining() ? b : a;
    }

    private static TokenUsage.Outcome outcome(HttpServletRequest request, int status) {
        if (status == 401 || status == 403 || request.getAttribute(TokenPrincipal.DENIED_ATTRIBUTE) != null) {
            return TokenUsage.Outcome.DENIED;
        }
        if (status == 429) {
            return TokenUsage.Outcome.LIMITED;
        }
        return status >= 500 ? TokenUsage.Outcome.ERROR : TokenUsage.Outcome.OK;
    }

    private void refuse(HttpServletResponse response, TokenPrincipal token, long retryAfter, String detail)
            throws IOException {
        usage.recordRequest(token.tokenId(), TokenUsage.Outcome.LIMITED);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", Long.toString(retryAfter));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(
                response.getOutputStream(),
                Problems.of(HttpStatus.TOO_MANY_REQUESTS, "rate-limited", "Too many requests", detail));
    }
}
