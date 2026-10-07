package io.github.sudoitir.artemisstudio.feature.apitokens;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.kernel.security.Grant;
import io.github.sudoitir.artemisstudio.kernel.security.PersonalTokens;
import io.github.sudoitir.artemisstudio.kernel.security.ScopeIds;
import io.github.sudoitir.artemisstudio.kernel.security.TokenGrant;
import io.github.sudoitir.artemisstudio.support.AccountIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** A token's principal, rebuilt from its id for work that outlives the request: only while the token still counts. */
class TokenPrincipalIntegrationTest extends AccountIntegrationTest {

    @Autowired
    ApiTokenService tokens;

    @Autowired
    PersonalTokens personalTokens;

    private UUID owner;

    private ApiTokenService.Minted mint() {
        owner = newUser("tp-" + UUID.randomUUID());
        return tokens.mint(
                owner,
                "tp-token",
                Instant.now().plusSeconds(3600),
                List.of(new TokenGrant(Grant.ScopeType.GLOBAL, ScopeIds.GLOBAL, "cluster:read", null, null)),
                List.of("list_queues"),
                true);
    }

    @Test
    void aLiveTokenYieldsItsOwnerNarrowedToItsGrants() {
        UUID id = mint().entity().getId();

        var principal = personalTokens.principal(id).orElseThrow();

        assertThat(principal.userId()).isEqualTo(owner);
        assertThat(principal.tokenId()).isEqualTo(id);
        assertThat(principal.tokenName()).isEqualTo("tp-token");
        assertThat(principal.grants()).extracting(TokenGrant::action).containsExactly("cluster:read");
        assertThat(principal.mcpTools()).containsExactly("list_queues");
    }

    @Test
    void aRevokedTokenYieldsNothing() {
        UUID id = mint().entity().getId();

        tokens.revoke(owner, id);

        assertThat(personalTokens.principal(id)).isEmpty();
    }

    @Test
    void anExpiredTokenYieldsNothing() {
        UUID id = mint().entity().getId();

        jdbc.sql("UPDATE api_token SET expires_at = now() - interval '1 minute' WHERE id = ?")
                .param(id)
                .update();

        assertThat(personalTokens.principal(id)).isEmpty();
    }

    @Test
    void aTokenWhoseOwnerIsDisabledYieldsNothing() {
        UUID id = mint().entity().getId();

        jdbc.sql("UPDATE app_user SET disabled = true WHERE id = ?")
                .param(owner)
                .update();

        assertThat(personalTokens.principal(id)).isEmpty();
        assertThat(personalTokens.principal(UUID.randomUUID())).isEmpty();
    }
}
