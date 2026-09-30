package io.github.sudoitir.artemisstudio.kernel.security.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudoitir.artemisstudio.kernel.security.StudioPrincipal;
import io.github.sudoitir.artemisstudio.support.PostgresIntegrationTest;
import jakarta.servlet.FilterChain;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** Idempotency keys (api-contract spec, ADR-0150) against a real Postgres, driving the filter directly. */
class IdempotencyFilterIntegrationTest extends PostgresIntegrationTest {

    private static final String PATH = "/api/v1/test/things";

    @Autowired
    IdempotencyFilter filter;

    @Autowired
    JdbcTemplate jdbc;

    private final AtomicInteger applied = new AtomicInteger();
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    /** A handler that applies the mutation once per call and answers 201 with the count. */
    private FilterChain create() {
        return (req, res) -> {
            int n = applied.incrementAndGet();
            ((jakarta.servlet.http.HttpServletResponse) res).setStatus(201);
            res.setContentType("application/json");
            res.getWriter().write("{\"n\":" + n + "}");
        };
    }

    private MockHttpServletResponse post(UUID user, String key, String query, String body, FilterChain chain)
            throws Exception {
        return send(user, "POST", PATH, key, query, body, "application/json", chain);
    }

    private MockHttpServletResponse send(
            UUID user,
            String method,
            String path,
            String key,
            String query,
            String body,
            String type,
            FilterChain chain)
            throws Exception {
        StudioPrincipal principal = new StudioPrincipal(user, "u-" + user, Set.of(), false);
        SecurityContextHolder.getContext()
                .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, Set.of()));
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setQueryString(query);
        request.setContentType(type);
        request.setContent(body.getBytes());
        if (key != null) {
            request.addHeader("Idempotency-Key", key);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    private static String type(MockHttpServletResponse response) throws Exception {
        return response.getContentAsString();
    }

    @Test
    void aRepeatAppliesOnceAndReplaysTheFirstResult() throws Exception {
        String key = UUID.randomUUID().toString();

        MockHttpServletResponse first = post(alice, key, null, "{\"a\":1}", create());
        MockHttpServletResponse repeat = post(alice, key, null, "{\"a\":1}", create());

        assertThat(applied).hasValue(1);
        assertThat(first.getHeader("Idempotent-Replayed")).isNull();
        assertThat(repeat.getStatus()).isEqualTo(201);
        assertThat(repeat.getContentAsString()).isEqualTo("{\"n\":1}").isEqualTo(first.getContentAsString());
        assertThat(repeat.getContentType()).startsWith("application/json");
        assertThat(repeat.getHeader("Idempotent-Replayed")).isEqualTo("true");
    }

    @Test
    void theSameKeyWithAnotherBodyIsRefused() throws Exception {
        String key = UUID.randomUUID().toString();
        post(alice, key, null, "{\"a\":1}", create());

        MockHttpServletResponse other = post(alice, key, null, "{\"a\":2}", create());

        assertThat(other.getStatus()).isEqualTo(422);
        assertThat(type(other)).contains("idempotency-key-reused");
        assertThat(applied).hasValue(1);
    }

    @Test
    void aRepeatWhileTheFirstIsRunningGets409() throws Exception {
        String key = UUID.randomUUID().toString();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        FilterChain slow = (req, res) -> {
            started.countDown();
            try {
                finish.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            applied.incrementAndGet();
            ((jakarta.servlet.http.HttpServletResponse) res).setStatus(201);
        };
        CompletableFuture<MockHttpServletResponse> first = CompletableFuture.supplyAsync(() -> {
            try {
                return post(alice, key, null, "{}", slow);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();

        MockHttpServletResponse repeat = post(alice, key, null, "{}", create());
        finish.countDown();

        assertThat(repeat.getStatus()).isEqualTo(409);
        assertThat(repeat.getHeader("Retry-After")).isEqualTo("1");
        assertThat(type(repeat)).contains("idempotency-in-progress");
        assertThat(first.get(30, TimeUnit.SECONDS).getStatus()).isEqualTo(201);
        assertThat(applied).hasValue(1);
    }

    @Test
    void anotherUsersSameKeyRunsIndependently() throws Exception {
        String key = UUID.randomUUID().toString();

        MockHttpServletResponse a = post(alice, key, null, "{}", create());
        MockHttpServletResponse b = post(bob, key, null, "{}", create());

        assertThat(applied).hasValue(2);
        assertThat(a.getContentAsString()).isEqualTo("{\"n\":1}");
        assertThat(b.getContentAsString()).isEqualTo("{\"n\":2}");
        assertThat(b.getHeader("Idempotent-Replayed")).isNull();
    }

    @Test
    void aDryRunsKeyIsNotTheRealRunsKey() throws Exception {
        String key = UUID.randomUUID().toString();
        post(alice, key, "dryRun=true", "{}", create());

        MockHttpServletResponse real = post(alice, key, null, "{}", create());

        assertThat(real.getStatus()).isEqualTo(422);
        assertThat(type(real)).contains("idempotency-key-reused");
        assertThat(applied).hasValue(1);
    }

    @Test
    void theOrderOfQueryParametersDoesNotMatter() throws Exception {
        String key = UUID.randomUUID().toString();
        post(alice, key, "a=1&b=2", "{}", create());

        MockHttpServletResponse repeat = post(alice, key, "b=2&a=1", "{}", create());

        assertThat(repeat.getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(applied).hasValue(1);
    }

    @Test
    void aServerErrorReleasesTheKeySoTheRetryRuns() throws Exception {
        String key = UUID.randomUUID().toString();
        FilterChain failing = (req, res) -> {
            applied.incrementAndGet();
            ((jakarta.servlet.http.HttpServletResponse) res).setStatus(500);
        };

        MockHttpServletResponse failed = post(alice, key, null, "{}", failing);
        MockHttpServletResponse retry = post(alice, key, null, "{}", create());

        assertThat(failed.getStatus()).isEqualTo(500);
        assertThat(retry.getStatus()).isEqualTo(201);
        assertThat(retry.getHeader("Idempotent-Replayed")).isNull();
        assertThat(applied).hasValue(2);
    }

    @Test
    void anExceptionReleasesTheKeySoTheRetryRuns() throws Exception {
        String key = UUID.randomUUID().toString();
        FilterChain throwing = (req, res) -> {
            throw new IllegalStateException("boom");
        };

        try {
            post(alice, key, null, "{}", throwing);
        } catch (IllegalStateException _) {
            // the container answers it
        }
        MockHttpServletResponse retry = post(alice, key, null, "{}", create());

        assertThat(retry.getStatus()).isEqualTo(201);
        assertThat(applied).hasValue(1);
    }

    @Test
    void aMultipartRequestWithAKeyIsRefused() throws Exception {
        MockHttpServletResponse response = send(
                alice,
                "POST",
                PATH,
                UUID.randomUUID().toString(),
                null,
                "x",
                "multipart/form-data; boundary=x",
                create());

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(type(response)).contains("idempotency-unsupported");
        assertThat(applied).hasValue(0);
    }

    @Test
    void aKeyThatIsNotPrintableAsciiOrTooLongIsRefused() throws Exception {
        for (String key : new String[] {"café", "x".repeat(256), "tab\there"}) {
            MockHttpServletResponse response = post(alice, key, null, "{}", create());
            assertThat(response.getStatus()).as(key).isEqualTo(400);
            assertThat(type(response)).contains("invalid-idempotency-key");
        }
        assertThat(applied).hasValue(0);
    }

    @Test
    void thePluginGatewayAndRequestsWithoutAKeyPassUntouched() throws Exception {
        String key = UUID.randomUUID().toString();
        FilterChain plain = (req, res) -> applied.incrementAndGet();

        send(alice, "POST", "/api/v1/p/acme/things", key, null, "{}", "application/json", plain);
        send(alice, "POST", "/api/v1/p/acme/things", key, null, "{}", "application/json", plain);
        send(alice, "POST", "/api/v1/clusters/c1/p/acme/things", key, null, "{}", "application/json", plain);
        send(alice, "POST", "/api/v1/clusters/c1/p/acme/things", key, null, "{}", "application/json", plain);
        post(alice, null, null, "{}", plain);
        post(alice, null, null, "{}", plain);

        assertThat(applied).hasValue(6);
    }

    @Test
    void theHandlerStillReadsTheBody() throws Exception {
        StringBuilder seen = new StringBuilder();
        FilterChain echo =
                (req, res) -> seen.append(new String(req.getInputStream().readAllBytes()));

        post(alice, UUID.randomUUID().toString(), null, "{\"a\":1}", echo);

        assertThat(seen).hasToString("{\"a\":1}");
    }

    private void row(UUID user, String key, String state, String age) {
        jdbc.update(
                "INSERT INTO idempotency_record (user_id, idem_key, fingerprint, state, status, body, created_at)"
                        + " VALUES (?, ?, 'old', ?, 201, '{\"n\":99}'::bytea, now() - ?::interval)",
                user,
                key,
                state,
                age);
    }

    @Test
    void aClaimAbandonedPastTheLeaseIsTakenOver() throws Exception {
        String key = UUID.randomUUID().toString();
        row(alice, key, "PENDING", "11 minutes");

        MockHttpServletResponse response = post(alice, key, null, "{}", create());

        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(applied).hasValue(1);
        assertThat(post(alice, key, null, "{}", create()).getHeader("Idempotent-Replayed"))
                .isEqualTo("true");
    }

    @Test
    void aKeyOlderThanADayIsForgotten() throws Exception {
        String key = UUID.randomUUID().toString();
        row(alice, key, "DONE", "25 hours");

        MockHttpServletResponse response = post(alice, key, null, "{}", create());

        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(response.getContentAsString()).isEqualTo("{\"n\":1}");
        assertThat(response.getHeader("Idempotent-Replayed")).isNull();
        assertThat(post(alice, key, null, "{}", create()).getHeader("Idempotent-Replayed"))
                .isEqualTo("true");
        assertThat(applied).hasValue(1);
    }

    @Test
    void aReplayCarriesLocationAndETagButNeverACookie() throws Exception {
        String key = UUID.randomUUID().toString();
        FilterChain creating = (req, res) -> {
            var response = (jakarta.servlet.http.HttpServletResponse) res;
            response.setStatus(201);
            response.setHeader("Location", "/api/v1/things/7");
            response.setHeader("ETag", "\"v1\"");
            response.setHeader("Set-Cookie", "SESSION=secret");
        };
        post(alice, key, null, "{}", creating);

        MockHttpServletResponse replay = post(alice, key, null, "{}", create());

        assertThat(replay.getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.getHeader("Location")).isEqualTo("/api/v1/things/7");
        assertThat(replay.getHeader("ETag")).isEqualTo("\"v1\"");
        assertThat(replay.getHeader("Set-Cookie")).isNull();
    }

    @Test
    void anAnswerOverEightMiBIsNotRecordedSoTheRetryRuns() throws Exception {
        String key = UUID.randomUUID().toString();
        FilterChain huge = (req, res) -> {
            applied.incrementAndGet();
            ((jakarta.servlet.http.HttpServletResponse) res).setStatus(200);
            res.getWriter().write("x".repeat(9 * 1024 * 1024));
        };

        MockHttpServletResponse first = post(alice, key, null, "{}", huge);
        MockHttpServletResponse retry = post(alice, key, null, "{}", huge);

        assertThat(first.getContentLength()).isPositive();
        assertThat(retry.getHeader("Idempotent-Replayed")).isNull();
        assertThat(applied).hasValue(2);
    }
}
