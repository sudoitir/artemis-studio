package io.github.sudoitir.artemisstudio.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.sudoitir.artemisstudio.persist.AppUserEntity;
import io.github.sudoitir.artemisstudio.persist.AppUserRepository;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import jakarta.servlet.http.Cookie;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The login/logout/me/password-change flow against the real
 * {@code SecurityFilterChain} (task 3.12) — login success/failure, the
 * throttle lockout, {@code 423} until password change and its self-unlock,
 * logout invalidating the session, and CSRF rejection without the header.
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

    /**
     * Tracks CSRF cookies and the servlet session across requests, the way a
     * browser would. MockMvc does not resolve a session from a JSESSIONID
     * cookie the way a real container does — the documented pattern is to
     * carry the {@link MockHttpSession} object itself between requests.
     */
    private static final class Session {
        private final Map<String, Cookie> cookies = new LinkedHashMap<>();
        private MockHttpSession session = new MockHttpSession();

        Cookie[] jar() {
            return cookies.values().toArray(new Cookie[0]);
        }

        String csrf() {
            Cookie c = cookies.get("XSRF-TOKEN");
            if (c == null) throw new IllegalStateException("no XSRF-TOKEN cookie captured yet");
            return c.getValue();
        }

        MockHttpSession httpSession() {
            return session;
        }

        void capture(MvcResult result) {
            Cookie[] fresh = result.getResponse().getCookies();
            if (fresh != null) {
                for (Cookie c : fresh) cookies.put(c.getName(), c);
            }
            if (result.getRequest().getSession(false) instanceof MockHttpSession s) {
                session = s;
            }
        }
    }

    @Test
    void loginSucceedsAndMeReflectsThePrincipal() throws Exception {
        newUser("auth-ok", "correct-horse-battery");
        MockMvc mvc = mvc();
        Session session = new Session();
        session.capture(mvc.perform(get("/api/v1/clusters").session(session.httpSession()))
                .andReturn());

        session.capture(mvc.perform(post("/api/v1/auth/login")
                        .session(session.httpSession())
                        .cookie(session.jar())
                        .header("X-XSRF-TOKEN", session.csrf())
                        .contentType("application/json")
                        .content("{\"username\":\"auth-ok\",\"password\":\"correct-horse-battery\"}"))
                .andExpect(status().isOk())
                .andReturn());

        mvc.perform(get("/api/v1/auth/me").session(session.httpSession()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("auth-ok")));
    }

    @Test
    void loginFailsWithWrongPassword() throws Exception {
        newUser("auth-bad", "correct-horse-battery");
        MockMvc mvc = mvc();
        Session session = new Session();
        session.capture(mvc.perform(get("/api/v1/clusters").session(session.httpSession()))
                .andReturn());

        mvc.perform(post("/api/v1/auth/login")
                        .session(session.httpSession())
                        .cookie(session.jar())
                        .header("X-XSRF-TOKEN", session.csrf())
                        .contentType("application/json")
                        .content("{\"username\":\"auth-bad\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginWithNoCsrfHeaderIsRejected() throws Exception {
        newUser("auth-csrf", "correct-horse-battery");
        MockMvc mvc = mvc();
        Session session = new Session();
        session.capture(mvc.perform(get("/api/v1/clusters").session(session.httpSession()))
                .andReturn());

        mvc.perform(post("/api/v1/auth/login")
                        .session(session.httpSession())
                        .cookie(session.jar())
                        .contentType("application/json")
                        .content("{\"username\":\"auth-csrf\",\"password\":\"correct-horse-battery\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void locksOutAfterRepeatedFailuresEvenWithTheCorrectPassword() throws Exception {
        newUser("auth-lockout", "correct-horse-battery");
        MockMvc mvc = mvc();
        Session session = new Session();
        session.capture(mvc.perform(get("/api/v1/clusters").session(session.httpSession()))
                .andReturn());

        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/v1/auth/login")
                            .session(session.httpSession())
                            .cookie(session.jar())
                            .header("X-XSRF-TOKEN", session.csrf())
                            .contentType("application/json")
                            .content("{\"username\":\"auth-lockout\",\"password\":\"wrong\"}"))
                    .andExpect(status().isUnauthorized());
        }

        mvc.perform(post("/api/v1/auth/login")
                        .session(session.httpSession())
                        .cookie(session.jar())
                        .header("X-XSRF-TOKEN", session.csrf())
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
        Session session = new Session();
        session.capture(mvc.perform(get("/api/v1/clusters").session(session.httpSession()))
                .andReturn());

        session.capture(mvc.perform(post("/api/v1/auth/login")
                        .session(session.httpSession())
                        .cookie(session.jar())
                        .header("X-XSRF-TOKEN", session.csrf())
                        .contentType("application/json")
                        .content("{\"username\":\"auth-locked-pwd\",\"password\":\"temp-password-1\"}"))
                .andExpect(status().isOk())
                .andReturn());

        mvc.perform(get("/api/v1/clusters").session(session.httpSession())).andExpect(status().isLocked());
        mvc.perform(get("/api/v1/auth/me").session(session.httpSession())).andExpect(status().isOk());

        session.capture(mvc.perform(post("/api/v1/auth/password")
                        .session(session.httpSession())
                        .cookie(session.jar())
                        .header("X-XSRF-TOKEN", session.csrf())
                        .contentType("application/json")
                        .content("{\"currentPassword\":\"temp-password-1\",\"newPassword\":\"new-real-password-2\"}"))
                .andExpect(status().isNoContent())
                .andReturn());

        // The same session, immediately, with no fresh login — the exact bug this
        // session found and fixed (AuthService.changePassword re-authenticates).
        mvc.perform(get("/api/v1/clusters").session(session.httpSession())).andExpect(status().isOk());
    }

    @Test
    void logoutInvalidatesTheSession() throws Exception {
        newUser("auth-logout", "correct-horse-battery");
        MockMvc mvc = mvc();
        Session session = new Session();
        session.capture(mvc.perform(get("/api/v1/clusters").session(session.httpSession()))
                .andReturn());

        session.capture(mvc.perform(post("/api/v1/auth/login")
                        .session(session.httpSession())
                        .cookie(session.jar())
                        .header("X-XSRF-TOKEN", session.csrf())
                        .contentType("application/json")
                        .content("{\"username\":\"auth-logout\",\"password\":\"correct-horse-battery\"}"))
                .andExpect(status().isOk())
                .andReturn());

        mvc.perform(post("/api/v1/auth/logout")
                        .session(session.httpSession())
                        .cookie(session.jar())
                        .header("X-XSRF-TOKEN", session.csrf()))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/clusters").session(session.httpSession())).andExpect(status().isUnauthorized());
    }
}
