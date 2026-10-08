package io.github.sudoitir.artemisstudio.kernel.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.kernel.security.UserIdentityFacts.Identity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** What tells two accounts apart as people: creation time, email, linked external identity, and whether enabled. */
class UserIdentityFactsIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    UserIdentityFacts facts;

    @Autowired
    AppUserRepository users;

    @Test
    void anExternalAccountCarriesItsProviderAndSubject() {
        String name = "idf-" + UUID.randomUUID();
        UUID id = users.save(AppUserEntity.external("corp-sso", "subject-1", name, "Ann@Example.test"))
                .getId();

        var found = facts.of(id).orElseThrow();

        assertThat(found.identities()).containsExactly(new Identity("corp-sso", "subject-1"));
        assertThat(found.email()).isEqualTo("Ann@Example.test");
        assertThat(found.enabled()).isTrue();
        assertThat(found.createdAt()).isNotNull();
    }

    @Test
    void aLocalAccountHasNoExternalIdentityAndADisabledOneIsNotEnabled() {
        AppUserEntity local = AppUserEntity.local("idf-" + UUID.randomUUID(), null, "{noop}x");
        local.setDisabled(true);
        UUID id = users.save(local).getId();

        var found = facts.of(id).orElseThrow();

        assertThat(found.identities()).isEmpty();
        assertThat(found.email()).isNull();
        assertThat(found.enabled()).isFalse();
        assertThat(facts.of(UUID.randomUUID())).isEmpty();
    }
}
