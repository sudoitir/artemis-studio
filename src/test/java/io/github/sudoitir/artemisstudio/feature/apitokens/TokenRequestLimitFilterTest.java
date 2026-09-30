package io.github.sudoitir.artemisstudio.feature.apitokens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sudoitir.artemisstudio.kernel.security.TokenPrincipal;
import io.github.sudoitir.artemisstudio.kernel.settings.SettingsService;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

/** Per-token and per-user request limits (api-tokens spec, ADR-0136). */
class TokenRequestLimitFilterTest {

    private final SettingsService settings = mock(SettingsService.class);
    private final TokenUsage usage = mock(TokenUsage.class);
    private final TokenRequestLimitFilter filter =
            new TokenRequestLimitFilter(settings, usage, JsonMapper.builder().build());
    private final UUID owner = UUID.randomUUID();

    @BeforeEach
    void limits() {
        when(settings.intValue(ApiTokensSettings.TOKEN_REQUESTS_PER_MINUTE)).thenReturn(2);
        when(settings.intValue(ApiTokensSettings.USER_REQUESTS_PER_MINUTE)).thenReturn(3);
        when(settings.intValue(ApiTokensSettings.TOKEN_CONCURRENCY)).thenReturn(8);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aTokenOverItsRateGets429WithTheLimitHeaders() throws Exception {
        UUID token = UUID.randomUUID();

        MockHttpServletResponse first = send(token);
        send(token);
        MockHttpServletResponse third = send(token);

        assertThat(first.getStatus()).isEqualTo(200);
        assertThat(first.getHeader("RateLimit-Limit")).isEqualTo("2");
        assertThat(first.getHeader("RateLimit-Remaining")).isEqualTo("1");
        assertThat(first.getHeader("RateLimit-Reset")).isNotNull();
        assertThat(third.getStatus()).isEqualTo(429);
        assertThat(third.getHeader("RateLimit-Remaining")).isEqualTo("0");
        assertThat(third.getHeader("Retry-After")).isNotNull();
        assertThat(third.getContentAsString()).contains("rate-limited");
    }

    @Test
    void aFloodedTokenLeavesTheOwnersOtherTokenAloneUntilTheUserLimit() throws Exception {
        UUID flooded = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        send(flooded);
        send(flooded);
        assertThat(send(flooded).getStatus()).isEqualTo(429);

        assertThat(send(other).getStatus()).isEqualTo(200);
        // Three requests of the owner's have now passed: the user limit refuses the fourth.
        assertThat(send(other).getStatus()).isEqualTo(429);
    }

    @Test
    void aSessionRequestIsNotLimited() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/clusters"), response, new MockFilterChain());

        assertThat(response.getHeader("RateLimit-Limit")).isNull();
    }

    private MockHttpServletResponse send(UUID tokenId) throws Exception {
        TokenPrincipal principal = new TokenPrincipal(owner, "ada", Set.of(), tokenId, "ci", Set.of());
        SecurityContextHolder.getContext()
                .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, Set.of()));
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/clusters"), response, new MockFilterChain());
        return response;
    }
}
