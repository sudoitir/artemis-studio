package io.github.sudoitir.artemisstudio.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.sudoitir.artemisstudio.persist.AppUserEntity;
import io.github.sudoitir.artemisstudio.persist.AppUserRepository;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
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

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(webContext)
                .apply(springSecurity())
                .build();
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
