package io.github.sudoitir.artemisstudio.feature.identityoidc;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A single-sign-on step-up is accepted only for the same account, freshly signed in (ADR-0103). */
class OidcStepUpTest {

    private final OidcStepUp.Pending pending = new OidcStepUp.Pending(
            UUID.randomUUID(),
            "keycloak",
            "subject-1",
            "/admin?tab=plugins",
            Instant.now().minusSeconds(20));

    @Test
    void theSameAccountSignedInAfterTheStepUpBeganIsAccepted() {
        assertThat(OidcStepUp.verify(
                        pending, "keycloak", "subject-1", Instant.now().minusSeconds(5)))
                .isEmpty();
    }

    @Test
    void anotherAccountIsRefused() {
        assertThat(OidcStepUp.verify(pending, "keycloak", "subject-2", Instant.now()))
                .isPresent();
        assertThat(OidcStepUp.verify(pending, "other-idp", "subject-1", Instant.now()))
                .isPresent();
    }

    @Test
    void aMissingAuthTimeFailsClosed() {
        assertThat(OidcStepUp.verify(pending, "keycloak", "subject-1", null))
                .get()
                .asString()
                .contains("auth_time");
    }

    @Test
    void aSignInFromBeforeTheStepUpIsRefused() {
        assertThat(OidcStepUp.verify(
                        pending, "keycloak", "subject-1", Instant.now().minusSeconds(3600)))
                .isPresent();
    }

    @Test
    void returnToIsOnlyEverASameOriginPath() {
        assertThat(OidcStepUp.safeReturnTo("/admin?tab=plugins")).isEqualTo("/admin?tab=plugins");
        assertThat(OidcStepUp.safeReturnTo("https://evil.example/")).isEqualTo("/");
        assertThat(OidcStepUp.safeReturnTo("//evil.example/")).isEqualTo("/");
        assertThat(OidcStepUp.safeReturnTo("/\\evil.example")).isEqualTo("/");
        assertThat(OidcStepUp.safeReturnTo(null)).isEqualTo("/");
    }
}
