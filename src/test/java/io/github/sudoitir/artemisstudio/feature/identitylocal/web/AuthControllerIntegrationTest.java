package io.github.sudoitir.artemisstudio.feature.identitylocal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.sudoitir.artemisstudio.kernel.security.SessionAuthentication;
import io.github.sudoitir.artemisstudio.kernel.security.SessionFacts;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The login/logout/me/password-change flow against the real
 * {@code SecurityFilterChain} (task 3.12) — login success/failure, the
 * throttle lockout, {@code 423} until password change and its self-unlock,
 * logout invalidating the session, and CSRF rejection without a token.
 *
 * <p>CSRF is exercised with {@link
 * org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors#csrf()
 * csrf()} — the same repository-agnostic pattern as {@code EndpointProtectionTest}
 * — rather than by round-tripping the {@code XSRF-TOKEN} response cookie through
 * MockMvc, which {@code CsrfFilter} does not re-issue reliably once a request is
 * short-circuited (401) before the token is read.
 */
class AuthControllerIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    AppUserRepository users;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    SessionAuthentication sessions;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(webContext)
                .apply(springSecurity())
                .build();
    }

    @Autowired
    FindByIndexNameSessionRepository<? extends Session> sessionStore;

    private MockHttpSession signIn(MockMvc mvc, String username, String password) throws Exception {
        return signIn(mvc, new MockHttpSession(), username, password);
    }

    private MockHttpSession signIn(MockMvc mvc, MockHttpSession session, String username, String password)
            throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                        .session(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)))
                .andExpect(status().isOk());
        return session;
    }

    private static ResultActions changePassword(MockMvc mvc, MockHttpSession session, String current, String next)
            throws Exception {
        return mvc.perform(post("/api/v1/auth/password")
                .session(session)
                .with(csrf())
                .contentType("application/json")
                .content("{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(current, next)));
    }

    private static <S extends Session> void openSession(
            FindByIndexNameSessionRepository<S> repository, String username) {
        S session = repository.createSession();
        session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, username);
        repository.save(session);
    }

    private void newUser(String username, String password) {
        AppUserEntity user =
                AppUserEntity.local(username, username + "@example.test", passwordEncoder.encode(password));
        user.setMustChangePassword(false);
        users.save(user);
    }

    @Test
    void loginSucceedsAndMeReflectsThePrincipal() throws Exception {
        newUser("auth-ok", "correct-horse-battery");
        MockMvc mvc = mvc();
        MockHttpSession session = new MockHttpSession();

        mvc.perform(post("/api/v1/auth/login")
                        .session(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"username\":\"auth-ok\",\"password\":\"correct-horse-battery\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("auth-ok")));
    }

    @Test
    void loginIssuesANewSessionId() throws Exception {
        newUser("auth-fixation", "correct-horse-battery");
        MockHttpSession session = new MockHttpSession();
        String before = session.getId();

        mvc().perform(post("/api/v1/auth/login")
                        .session(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"username\":\"auth-fixation\",\"password\":\"correct-horse-battery\"}"))
                .andExpect(status().isOk());

        assertThat(session.getId()).isNotEqualTo(before);
    }

    @Test
    void loginFailsWithWrongPassword() throws Exception {
        newUser("auth-bad", "correct-horse-battery");

        mvc().perform(post("/api/v1/auth/login")
                        .session(new MockHttpSession())
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"username\":\"auth-bad\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginWithNoCsrfTokenIsRejected() throws Exception {
        newUser("auth-csrf", "correct-horse-battery");

        mvc().perform(post("/api/v1/auth/login")
                        .session(new MockHttpSession())
                        .contentType("application/json")
                        .content("{\"username\":\"auth-csrf\",\"password\":\"correct-horse-battery\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void locksOutAfterRepeatedFailuresEvenWithTheCorrectPassword() throws Exception {
        newUser("auth-lockout", "correct-horse-battery");
        MockMvc mvc = mvc();
        MockHttpSession session = new MockHttpSession();

        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/v1/auth/login")
                            .session(session)
                            .with(csrf())
                            .contentType("application/json")
                            .content("{\"username\":\"auth-lockout\",\"password\":\"wrong\"}"))
                    .andExpect(status().isUnauthorized());
        }

        mvc.perform(post("/api/v1/auth/login")
                        .session(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"username\":\"auth-lockout\",\"password\":\"correct-horse-battery\"}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void mustChangePasswordLocksEveryEndpointExceptTheEscapeHatchesAndPasswordChangeUnlocksImmediately()
            throws Exception {
        newUser("auth-locked-pwd", "temp-password-1");
        AppUserEntity user = users.findByUsername("auth-locked-pwd").orElseThrow();
        user.setMustChangePassword(true);
        users.save(user);

        MockMvc mvc = mvc();
        MockHttpSession session = new MockHttpSession();

        mvc.perform(post("/api/v1/auth/login")
                        .session(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"username\":\"auth-locked-pwd\",\"password\":\"temp-password-1\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/clusters").session(session)).andExpect(status().isLocked());
        // The browser must still load the SPA shell, or the change-password page never renders.
        mvc.perform(get("/change-password").session(session))
                .andExpect(
                        result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(HttpStatus.LOCKED.value()));
        mvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isOk());

        mvc.perform(post("/api/v1/auth/password")
                        .session(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"currentPassword\":\"temp-password-1\",\"newPassword\":\"new-real-password-2\"}"))
                .andExpect(status().isNoContent());

        // The same session, immediately, with no fresh login — the exact bug this
        // session found and fixed (AuthService.changePassword re-authenticates).
        mvc.perform(get("/api/v1/clusters").session(session)).andExpect(status().isOk());
    }

    @Test
    void loginClearsPendingStateFromAnEarlierAttempt() throws Exception {
        newUser("auth-pending", "correct-horse-battery");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionAuthentication.PENDING_PREFIX + "SECOND_FACTOR", "someone-else");
        session.setAttribute("unrelated", "kept");

        signIn(mvc(), session, "auth-pending", "correct-horse-battery");

        assertThat(session.getAttribute(SessionAuthentication.PENDING_PREFIX + "SECOND_FACTOR"))
                .isNull();
        assertThat(session.getAttribute("unrelated")).isEqualTo("kept");
    }

    @Test
    void passwordPolicyRejectsAWeakNewPasswordWithTheReason() throws Exception {
        newUser("auth-policy", "correct-horse-battery");
        MockMvc mvc = mvc();
        MockHttpSession session = signIn(mvc, "auth-policy", "correct-horse-battery");

        changePassword(mvc, session, "correct-horse-battery", "short")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(endsWith("password-policy")))
                .andExpect(jsonPath("$.detail").value("Use at least 12 characters."));
        changePassword(mvc, session, "correct-horse-battery", "qwerty123456")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("commonly used")));

        assertThat(passwordEncoder.matches(
                        "correct-horse-battery",
                        users.findByUsername("auth-policy").orElseThrow().getPasswordHash()))
                .isTrue();
    }

    @Test
    void changingThePasswordEndsTheUsersOtherSessionsOnly() throws Exception {
        newUser("auth-others", "correct-horse-battery");
        MockMvc mvc = mvc();
        MockHttpSession session = signIn(mvc, "auth-others", "correct-horse-battery");
        openSession(sessionStore, "auth-others");
        openSession(sessionStore, "auth-others");
        openSession(sessionStore, "auth-others-bystander");

        changePassword(mvc, session, "correct-horse-battery", "a-brand-new-passphrase")
                .andExpect(status().isNoContent());

        assertThat(sessionStore.findByPrincipalName("auth-others")).isEmpty();
        assertThat(sessionStore.findByPrincipalName("auth-others-bystander")).hasSize(1);
        mvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isOk());
    }

    @Test
    void changingThePasswordDoesNotRefreshStepUpFreshness() throws Exception {
        newUser("auth-fresh", "correct-horse-battery");
        MockMvc mvc = mvc();
        MockHttpSession session = signIn(mvc, "auth-fresh", "correct-horse-battery");
        var stale = ((SessionFacts) session.getAttribute(SessionAuthentication.FACTS))
                .withAuthenticatedAt(Instant.now().minusSeconds(600));
        session.setAttribute(SessionAuthentication.FACTS, stale);

        changePassword(mvc, session, "correct-horse-battery", "a-brand-new-passphrase")
                .andExpect(status().isNoContent());

        assertThat(session.getAttribute(SessionAuthentication.FACTS)).isEqualTo(stale);
        var request = new MockHttpServletRequest();
        request.setSession(session);
        assertThat(sessions.recentlyAuthenticated(request)).isFalse();
    }

    @Test
    void wrongCurrentPasswordsAreThrottledLikeLogins() throws Exception {
        newUser("auth-pwd-throttle", "correct-horse-battery");
        MockMvc mvc = mvc();
        MockHttpSession session = signIn(mvc, "auth-pwd-throttle", "correct-horse-battery");

        for (int i = 0; i < 5; i++) {
            changePassword(mvc, session, "wrong", "a-brand-new-passphrase").andExpect(status().isUnauthorized());
        }

        changePassword(mvc, session, "correct-horse-battery", "a-brand-new-passphrase")
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void logoutInvalidatesTheSession() throws Exception {
        newUser("auth-logout", "correct-horse-battery");
        MockMvc mvc = mvc();
        MockHttpSession session = new MockHttpSession();

        mvc.perform(post("/api/v1/auth/login")
                        .session(session)
                        .with(csrf())
                        .contentType("application/json")
                        .content("{\"username\":\"auth-logout\",\"password\":\"correct-horse-battery\"}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/auth/logout").session(session).with(csrf())).andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/clusters").session(session)).andExpect(status().isUnauthorized());
    }
}
