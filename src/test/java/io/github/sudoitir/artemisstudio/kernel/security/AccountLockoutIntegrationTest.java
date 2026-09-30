package io.github.sudoitir.artemisstudio.kernel.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserEntity;
import io.github.sudoitir.artemisstudio.kernel.security.internal.persistence.AppUserRepository;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The account lock (ADR-0143) against the real filter chain and database. Every attempt comes from
 * its own source address, because the in-memory throttle keyed by username and source would
 * otherwise answer 429 after five and the database counter would never be reached. The database
 * is read outside any transaction, so what is asserted is what was committed.
 */
class AccountLockoutIntegrationTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired
    WebApplicationContext webContext;

    @Autowired
    AppUserRepository users;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    AccountLockout lockout;

    @Autowired
    JdbcClient jdbc;

    private final AtomicInteger sources = new AtomicInteger();
    private MockMvc mvc;

    private MockMvc mvc() {
        if (mvc == null) {
            mvc = MockMvcBuilders.webAppContextSetup(webContext)
                    .apply(springSecurity())
                    .build();
        }
        return mvc;
    }

    private UUID newUser(String username) {
        AppUserEntity user =
                AppUserEntity.local(username, username + "@example.test", passwordEncoder.encode(PASSWORD));
        user.setMustChangePassword(false);
        return users.save(user).getId();
    }

    /** One sign-in attempt from a source address not used before. */
    private MvcResult login(String username, String password) throws Exception {
        return login(username, password, "10.9." + sources.incrementAndGet() / 250 + "." + sources.get() % 250);
    }

    private MvcResult login(String username, String password, String source) throws Exception {
        return mvc().perform(post("/api/v1/auth/login")
                        .session(new MockHttpSession())
                        .with(csrf())
                        .with(request -> {
                            request.setRemoteAddr(source);
                            return request;
                        })
                        .contentType("application/json")
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)))
                .andReturn();
    }

    private void fail(String username, int times) throws Exception {
        for (int i = 0; i < times; i++) {
            assertThat(login(username, "wrong").getResponse().getStatus()).isEqualTo(401);
        }
    }

    private AppUserEntity row(String username) {
        return users.findByUsername(username).orElseThrow();
    }

    private long audited(String action, String target) {
        return jdbc.sql("SELECT count(*) FROM audit_event WHERE action = ? AND target_name = ?")
                .params(action, target)
                .query(Long.class)
                .single();
    }

    @Test
    void failedSignInsAreCommittedNotRolledBackWithTheLogin() throws Exception {
        newUser("lock-committed");

        fail("lock-committed", 3);

        assertThat(row("lock-committed").getFailedLoginCount()).isEqualTo(3);
        assertThat(row("lock-committed").getLockedUntil()).isNull();
    }

    @Test
    void tenFailuresLockTheAccountForFifteenMinutesAndAuditIt() throws Exception {
        newUser("lock-ten");

        fail("lock-ten", 9);
        assertThat(row("lock-ten").getLockedUntil()).isNull();
        fail("lock-ten", 1);

        assertThat(row("lock-ten").getLockedUntil())
                .isBetween(
                        Instant.now().plus(Duration.ofMinutes(14)),
                        Instant.now().plus(Duration.ofMinutes(16)));
        assertThat(audited("ACCOUNT_LOCK", "lock-ten")).isEqualTo(1);
    }

    @Test
    void aLockedAccountRefusesTheCorrectPasswordExactlyLikeAWrongOneForAnUnknownUser() throws Exception {
        newUser("lock-same");
        fail("lock-same", 10);

        MvcResult locked = login("lock-same", PASSWORD);
        MvcResult unknown = login("lock-nobody", "wrong");

        assertThat(locked.getResponse().getStatus()).isEqualTo(401);
        assertThat(unknown.getResponse().getStatus()).isEqualTo(401);
        assertThat(locked.getResponse().getContentAsString())
                .isEqualTo(unknown.getResponse().getContentAsString());
        assertThat(locked.getResponse().getContentType())
                .isEqualTo(unknown.getResponse().getContentType());
    }

    @Test
    void aFailureWhileLockedNeitherExtendsTheLockNorCountsAgain() throws Exception {
        newUser("lock-steady");
        fail("lock-steady", 10);
        Instant until = row("lock-steady").getLockedUntil();

        fail("lock-steady", 2);

        assertThat(row("lock-steady").getLockedUntil()).isEqualTo(until);
        assertThat(row("lock-steady").getFailedLoginCount()).isEqualTo(10);
        assertThat(audited("ACCOUNT_LOCK", "lock-steady")).isEqualTo(1);
    }

    @Test
    void theLockLiftsWhenItExpiresAndTheCountStartsAgain() throws Exception {
        newUser("lock-expiry");
        fail("lock-expiry", 10);
        jdbc.sql("UPDATE app_user SET locked_until = now() - interval '1 minute' WHERE username = ?")
                .param("lock-expiry")
                .update();

        fail("lock-expiry", 1);
        assertThat(row("lock-expiry").getFailedLoginCount()).isEqualTo(1);
        assertThat(row("lock-expiry").getLockedUntil()).isNull();

        assertThat(login("lock-expiry", PASSWORD).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void aCorrectPasswordAfterTheLockExpiredSignsInAndClearsIt() throws Exception {
        newUser("lock-expired-ok");
        fail("lock-expired-ok", 10);
        jdbc.sql("UPDATE app_user SET locked_until = now() - interval '1 minute' WHERE username = ?")
                .param("lock-expired-ok")
                .update();

        assertThat(login("lock-expired-ok", PASSWORD).getResponse().getStatus()).isEqualTo(200);

        assertThat(row("lock-expired-ok").getFailedLoginCount()).isZero();
        assertThat(row("lock-expired-ok").getLockedUntil()).isNull();
    }

    @Test
    void aSuccessfulSignInResetsTheCount() throws Exception {
        newUser("lock-reset");
        fail("lock-reset", 4);

        assertThat(login("lock-reset", PASSWORD).getResponse().getStatus()).isEqualTo(200);

        assertThat(row("lock-reset").getFailedLoginCount()).isZero();
    }

    @Test
    void concurrentFailuresAreAllCountedAndTheLockIsSetAndAuditedOnce() throws Exception {
        UUID id = newUser("lock-race");

        try (var pool = Executors.newFixedThreadPool(20)) {
            List<Future<?>> done = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                done.add(pool.submit(() -> lockout.failed(id, "lock-race", new MockHttpServletRequest())));
            }
            for (Future<?> f : done) {
                f.get();
            }
        }

        assertThat(row("lock-race").getFailedLoginCount()).isEqualTo(10);
        assertThat(row("lock-race").getLockedUntil()).isAfter(Instant.now());
        assertThat(audited("ACCOUNT_LOCK", "lock-race")).isEqualTo(1);
    }

    @Test
    void anUnknownUsernameIsRefusedLikeAWrongPasswordAndLocksNothing() throws Exception {
        newUser("lock-known");

        MvcResult known = login("lock-known", "wrong");
        MvcResult unknown = login("lock-ghost", "wrong");

        assertThat(unknown.getResponse().getStatus())
                .isEqualTo(known.getResponse().getStatus());
        assertThat(unknown.getResponse().getContentAsString())
                .isEqualTo(known.getResponse().getContentAsString());
        assertThat(users.findByUsername("lock-ghost")).isEmpty();
    }

    @Test
    void failedLocalSignInsCannotLockASingleSignOnAccount() throws Exception {
        users.save(AppUserEntity.external("oidc-test", "subject-1", "lock-sso", null));

        fail("lock-sso", 10);

        assertThat(row("lock-sso").getFailedLoginCount()).isZero();
        assertThat(row("lock-sso").getLockedUntil()).isNull();
    }

    @Test
    void aThrottledAttemptIsAuditedAsFailedAndThrottled() throws Exception {
        newUser("lock-throttled");
        for (int i = 0; i < 5; i++) {
            assertThat(login("lock-throttled", "wrong", "10.8.0.1")
                            .getResponse()
                            .getStatus())
                    .isEqualTo(401);
        }

        assertThat(login("lock-throttled", "wrong", "10.8.0.1").getResponse().getStatus())
                .isEqualTo(429);

        assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action = 'LOGIN' AND target_name = ?"
                                + " AND outcome = 'FAILURE' AND error = 'throttled'")
                        .param("lock-throttled")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }
}
